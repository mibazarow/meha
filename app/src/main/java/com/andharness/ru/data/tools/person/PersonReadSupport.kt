package com.andharness.ru.data.tools.person

import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.Interaction
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentity
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.person.PersonResolver

/**
 * Read-side support shared by the person tools: builds the resolver candidate pool, renders the
 * honest resolution/profile/interaction text, and never guesses (an ambiguous match is reported as
 * ambiguous with its candidate list).
 */
internal class PersonReadSupport(
    private val personRepository: PersonRepository,
    private val identityRepository: PersonIdentityRepository,
    private val aliasRepository: PersonAliasRepository,
    private val interactionRepository: InteractionRepository,
) {

    /**
     * Builds the candidate pool and runs the pure resolver.
     *
     * The pool is deliberately bounded and honest about its limits: it is the union of the
     * name/alias matches, the owner of an exact phone/email/messenger handle and the most recently
     * updated people (the last one is what gives typo tolerance a chance on a small base).
     */
    suspend fun resolveQuery(
        query: String,
        channel: InteractionChannel?,
    ): PersonResolver.ResolveResult {
        val ids = LinkedHashSet<Long>()
        personRepository.searchByName(query, CANDIDATE_LIMIT).forEach { ids += it.id }
        personRepository.byAlias(query, CANDIDATE_LIMIT).forEach { ids += it.id }
        identityOwner(query)?.let { ids += it }
        personRepository.recent(CANDIDATE_LIMIT).forEach { ids += it.id }

        val candidates = ids.take(CANDIDATE_LIMIT).mapNotNull { buildCandidate(it) }
        return PersonResolver.resolve(
            query = query,
            candidates = candidates,
            channel = channel,
            now = System.currentTimeMillis(),
            limit = FIND_LIMIT,
        )
    }

    /** Person id owning the exact phone/email/messenger handle [query], or `null`. */
    private suspend fun identityOwner(query: String): Long? {
        IdentityNormalizer.normalizePhone(query)?.let { phone ->
            identityRepository.personIdFor(IdentityType.PHONE, phone)?.let { return it }
        }
        IdentityNormalizer.normalizeEmail(query)?.let { email ->
            identityRepository.personIdFor(IdentityType.EMAIL, email)?.let { return it }
        }
        IdentityNormalizer.normalizeMessenger(query)?.let { messenger ->
            identityRepository.personIdFor(IdentityType.MESSENGER, messenger)?.let { return it }
        }
        return null
    }

    private suspend fun buildCandidate(personId: Long): PersonResolver.PersonCandidate? {
        val person = personRepository.get(personId) ?: return null
        val identities = identityRepository.forPerson(personId)
        val aliases = aliasRepository.forPerson(personId).map { it.alias }
        val last = interactionRepository.recentFor(personId, 1).firstOrNull()
        val count = interactionRepository.countFor(personId)
        return PersonResolver.PersonCandidate(
            person = person,
            identities = identities,
            aliases = aliases,
            lastInteractionAt = last?.at,
            lastInteractionChannel = last?.channel,
            interactionCount = count,
        )
    }

    fun formatResolve(
        query: String,
        channel: InteractionChannel?,
        result: PersonResolver.ResolveResult,
    ): String = buildString {
        append("Резолв по «")
        append(query)
        append('»')
        if (channel != null) {
            append(" (канал ")
            append(channel.name)
            append(')')
        }
        appendLine(":")
        result.matches.forEachIndexed { index, match ->
            appendLine(summaryLine(index + 1, match))
        }
        appendLine()
        when (result.outcome) {
            PersonResolver.ResolveOutcome.RESOLVED -> {
                val best = result.best
                append("Однозначно: это ")
                append(best?.person?.displayName)
                append(" (person_id=")
                append(best?.person?.id)
                append("). Дальше используй этот person_id.")
            }
            PersonResolver.ResolveOutcome.AMBIGUOUS -> {
                append("Неоднозначно: под запрос подходит несколько человек. Спроси пользователя, кто ")
                append("именно, и назови кандидатов с их person_id — не угадывай молча.")
            }
            PersonResolver.ResolveOutcome.NOT_FOUND -> append(notFoundText(query))
        }
    }

    private fun summaryLine(index: Int, match: PersonResolver.PersonMatch): String = buildString {
        append(index)
        append(". #")
        append(match.person.id)
        append(' ')
        append(match.person.displayName)
        append(" — идентичности: ")
        append(identitiesText(match.identities))
        append(" — последнее взаимодействие: ")
        append(lastInteractionText(match.lastInteractionChannel, match.lastInteractionAt))
        append(" — взаимодействий: ")
        append(match.interactionCount)
        append(" — score ")
        append(String.format(java.util.Locale.US, "%.2f", match.score))
        append(" (")
        append(match.reason)
        append(')')
    }

    fun identitiesText(identities: List<PersonIdentity>): String {
        if (identities.isEmpty()) return "нет"
        return identities.joinToString(", ") { identity ->
            val pkg = identity.appPackage.takeIf { it.isNotEmpty() }
            if (pkg == null) {
                "${identity.type.name} ${identity.value}"
            } else {
                "${identity.type.name} ${identity.value} ($pkg)"
            }
        }
    }

    private fun lastInteractionText(channel: InteractionChannel?, at: Long?): String {
        if (at == null) return "нет"
        val stamp = formatDate(at)
        return if (channel == null) stamp else "$stamp ${channel.name}"
    }

    fun notFoundText(query: String): String =
        "Никого не нашёл по «$query». Возможные причины: персональная база ещё не проиндексирована " +
            "(проверь messaging_index_config) или такого человека нет среди контактов/SMS/звонков/" +
            "мессенджеров. Телефон/email/ник ищутся по нормализованной идентичности, имя и алиасы — " +
            "нечётко (опечатки, уменьшительные, транслит)."

    suspend fun formatProfile(person: Person, limit: Int): String {
        val identities = identityRepository.forPerson(person.id)
        val aliases = aliasRepository.forPerson(person.id).map { it.alias }
        val interactions = interactionRepository.recentFor(person.id, limit)

        return buildString {
            append('#')
            append(person.id)
            append(' ')
            appendLine(person.displayName)
            appendLine("Заметки: ${person.notes.ifBlank { "—" }}")
            appendLine("Алиасы: ${if (aliases.isEmpty()) "нет" else aliases.joinToString(", ")}")
            appendLine("Идентичности: ${identitiesText(identities)}")
            append("Последние взаимодействия (${interactions.size}):")
            if (interactions.isEmpty()) {
                append(" нет")
            } else {
                interactions.forEach { appendLine(); append("  - ").append(interactionText(it, person)) }
            }
            appendLine()
            append(
                "Честно: показаны только метаданные и краткое резюме (gist ≤ 80 символов); тело " +
                    "сообщения не хранится и не показывается."
            )
        }
    }

    fun interactionText(interaction: Interaction, person: Person): String = buildString {
        append(formatDate(interaction.at))
        append(' ')
        append(interaction.channel.name)
        append('/')
        append(interaction.direction.name)
        append(" · ")
        append('#')
        append(person.id)
        append(' ')
        append(person.displayName)
        val title = interaction.title?.takeIf { it.isNotBlank() }
        if (title != null) {
            append(" · ")
            append(title)
        }
        val summary = interaction.summary?.takeIf { it.isNotBlank() }
        if (summary != null) {
            append(" · ")
            append(summary)
        }
        val source = interaction.sourceApp?.takeIf { it.isNotBlank() }
        if (source != null) {
            append(" [")
            append(source)
            append(']')
        }
    }

    fun unknownPerson(personId: Long) = Person(
        id = personId,
        displayName = "персона удалена",
        notes = "",
        createdAt = 0L,
        updatedAt = 0L
    )

    fun formatDate(at: Long): String = formatTimestamp(at)

    private companion object {
        /** How many people form the resolver candidate pool (bounded, documented). */
        const val CANDIDATE_LIMIT = 40

        /** How many ranked candidates `person_find` reports. */
        const val FIND_LIMIT = 10
    }
}
