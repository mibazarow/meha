package com.andharness.ru.data.person

import androidx.room.withTransaction
import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.Interaction
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDedup
import com.andharness.ru.domain.person.InteractionDirection
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.MessageGist
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The **single entry point** of the incremental personal-base ingestion of *interactions*
 * (update.md §4.4).
 *
 * Every producer (SMS receiver, notification listener, backfill worker, tools) records its
 * interaction through this class instead of talking to the interaction repositories directly, so the
 * two rules of the interaction store are enforced in one place:
 *
 * 1. **only metadata + a bounded gist** are persisted here — the full body is never stored in
 *    `interactions`, logged or copied into `externalRef`/`title` ([MessageGist] caps the summary at
 *    80 characters). The **full** body of a captured message is persisted separately, in the
 *    encrypted `stored_messages` store ([com.andharness.ru.domain.messages.StoredMessagesRepository]),
 *    which the same producers write directly.
 * 2. an interaction carrying a stable [Interaction.externalRef] is **inserted once**
 *    ([InteractionDedup]).
 *
 * All methods are suspend and side-effect free with respect to logging: nothing here writes message
 * text to logcat.
 */
@Singleton
class PersonIndexer @Inject constructor(
    private val db: AppDatabase,
    private val personRepository: PersonRepository,
    private val identityRepository: PersonIdentityRepository,
    private val interactionRepository: InteractionRepository,
    private val configStore: MessagingIndexConfigStore
) {

    /** True when the user opted in to messenger/mail ingestion. */
    fun isEnabled(): Boolean = configStore.isEnabled()

    /** The person owning the (normalized) [value], or `null` when the handle is not indexed. */
    suspend fun personIdFor(
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE
    ): Long? = identityRepository.personIdFor(type, value, appPackage)

    /**
     * Creates a new person with [displayName] **without** an identity.
     *
     * Used by the contacts backfill, where a person first exists as a name and its phone/e-mail
     * handles are linked right after ([linkIdentity]).
     */
    suspend fun ensurePerson(displayName: String, notes: String = ""): Long =
        personRepository.insert(displayName.trim().ifEmpty { UNNAMED }, notes)

    /**
     * Links (normalized) [value] to an existing [personId].
     *
     * @return the identity id, or `null` when [value] is not a valid handle of [type].
     */
    suspend fun linkIdentity(
        personId: Long,
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE,
        label: String? = null
    ): Long? = identityRepository.insert(personId, type, value, appPackage, label)

    /**
     * Resolves the person owning the normalized [value] or creates a person and links the handle.
     *
     * [PersonIdentityRepository] normalizes and de-dupes, so repeated calls with the same handle
     * return the same person id.
     *
     * @return the person id, or `null` when [value] is not a valid handle of [type].
     */
    suspend fun upsertIdentity(
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE,
        label: String? = null
    ): Long? {
        val normalized = IdentityNormalizer.normalize(type, value, appPackage) ?: return null
        // Serialised with every other producer (notification scope, SMS receiver, backfill worker),
        // so the resolve-then-create cannot interleave and leave an orphan person behind.
        return db.withTransaction {
            resolveOrCreateIdentity(type, value, appPackage, label, normalized)
        }
    }

    /**
     * Resolves the person owning the already-normalized handle or creates one and links it.
     *
     * Must run inside an open transaction (`db.withTransaction`): the read-then-insert is only
     * atomic with respect to concurrent producers when it is serialised by Room's single write
     * connection. Room queues additional `withTransaction` blocks, so concurrent calls cannot
     * interleave here.
     * Reference: https://developer.android.com/reference/kotlin/androidx/room/RoomDatabase#withTransaction(kotlin.coroutines.SuspendFunction0)
     */
    private suspend fun resolveOrCreateIdentity(
        type: IdentityType,
        value: String,
        appPackage: String,
        label: String?,
        normalized: String
    ): Long {
        val existing = identityRepository.personIdFor(type, value, appPackage)
        if (existing != null) return existing

        val displayName = label?.trim()?.takeIf { it.isNotEmpty() } ?: normalized
        val personId = personRepository.insert(displayName)
        identityRepository.insert(personId, type, value, appPackage, label)
        // Defensive: the unique (type, value, appPackage) index makes a lost race impossible inside
        // the transaction, but if the handle turned out to belong to another person, drop the
        // orphan row we just created and return the real owner.
        val owner = identityRepository.personIdFor(type, value, appPackage)
        return if (owner != null && owner != personId) {
            personRepository.delete(personId)
            owner
        } else {
            personId
        }
    }

    /**
     * Records one interaction, creating the person/identity when needed.
     *
     * Dedup rule: when [externalRef] is non-blank and already indexed for the resolved person, the
     * row is skipped and `null` is returned (the caller can treat it as "already known").
     *
     * @param summary a full-ish description; it is **cut to a bounded gist** here, never stored raw.
     * @return the new interaction id, or `null` when nothing was inserted.
     */
    suspend fun recordInteraction(
        channel: InteractionChannel,
        direction: InteractionDirection,
        at: Long,
        personIdOrIdentity: PersonRef,
        title: String? = null,
        summary: String? = null,
        externalRef: String? = null,
        sourceApp: String? = null
    ): Long? {
        val ref = externalRef?.trim()?.takeIf { it.isNotEmpty() }
        // The dedup check and the insert run in one transaction, so two producers carrying the same
        // [externalRef] cannot both pass the check and insert a duplicate row.
        return db.withTransaction {
            val personId = when (personIdOrIdentity) {
                is PersonRef.Existing -> personIdOrIdentity.personId
                is PersonRef.Identity -> {
                    val normalized = IdentityNormalizer.normalize(
                        personIdOrIdentity.type,
                        personIdOrIdentity.value,
                        personIdOrIdentity.appPackage
                    ) ?: return@withTransaction null
                    resolveOrCreateIdentity(
                        type = personIdOrIdentity.type,
                        value = personIdOrIdentity.value,
                        appPackage = personIdOrIdentity.appPackage,
                        label = personIdOrIdentity.label,
                        normalized = normalized
                    )
                }
            }

            val alreadyIndexed = ref != null && interactionRepository.existsByExternalRef(personId, ref)
            if (InteractionDedup.isDuplicate(ref, alreadyIndexed)) return@withTransaction null

            interactionRepository.insert(
                Interaction(
                    id = 0,
                    personId = personId,
                    channel = channel,
                    direction = direction,
                    at = at,
                    title = title?.trim()?.takeIf { it.isNotEmpty() },
                    // The full text is bounded to a short gist RIGHT HERE; only the gist is persisted.
                    summary = MessageGist.bounded(summary),
                    externalRef = ref,
                    sourceApp = sourceApp?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
    }

    private companion object {
        const val UNNAMED = "Без имени"
    }
}
