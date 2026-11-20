package com.andharness.ru.data.tools.person

import android.util.Log
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.message.MessageChannelRequest

/**
 * Resolves the `to` recipient of a composed/scheduled message without ever guessing: an explicit
 * number is used as-is, a name goes through the resolver ([PersonReadSupport]) and an
 * ambiguous/unknown name is returned as a question.
 */
internal class PersonRecipientResolver(
    private val indexer: PersonIndexer,
    private val personRepository: PersonRepository,
    private val readSupport: PersonReadSupport,
) {

    /** A resolved, ready-to-use recipient of a composed message. */
    data class Recipient(
        val personId: Long?,
        val displayName: String,
        val phone: String?,
        val messenger: com.andharness.ru.domain.person.PersonIdentity?,
    )

    sealed interface RecipientResolution {
        data class Ready(val recipient: Recipient) : RecipientResolution
        data class NeedUser(val text: String) : RecipientResolution
        data class NotFound(val text: String) : RecipientResolution
    }

    suspend fun resolve(to: String, request: MessageChannelRequest): RecipientResolution {
        val phone = IdentityNormalizer.normalizePhone(to)
        if (phone != null) {
            val personId = runCatching { indexer.upsertIdentity(IdentityType.PHONE, to) }
                .onFailure { Log.w(TAG, "upsertIdentity failed for recipient", it) }
                .getOrNull()
            val name = personId?.let { personRepository.get(it)?.displayName } ?: to
            return RecipientResolution.Ready(Recipient(personId, name, phone, null))
        }

        val hint = when (request) {
            MessageChannelRequest.SMS -> InteractionChannel.SMS
            MessageChannelRequest.MESSENGER -> InteractionChannel.MESSENGER
            MessageChannelRequest.AUTO -> null
        }
        val result = readSupport.resolveQuery(to, hint)
        return when (result.outcome) {
            PersonResolver.ResolveOutcome.NOT_FOUND -> RecipientResolution.NotFound(
                readSupport.notFoundText(to) + " Отправлять некуда — уточни получателя."
            )
            PersonResolver.ResolveOutcome.AMBIGUOUS -> RecipientResolution.NeedUser(
                readSupport.formatResolve(to, hint, result) + " Ничего не отправлено."
            )
            PersonResolver.ResolveOutcome.RESOLVED -> {
                val best = result.best!!
                RecipientResolution.Ready(
                    Recipient(
                        personId = best.person.id,
                        displayName = best.person.displayName,
                        phone = best.identities.firstOrNull { it.type == IdentityType.PHONE }?.value,
                        messenger = best.identities.firstOrNull { it.type == IdentityType.MESSENGER },
                    )
                )
            }
        }
    }

    private companion object {
        private const val TAG = "Andharness"
    }
}
