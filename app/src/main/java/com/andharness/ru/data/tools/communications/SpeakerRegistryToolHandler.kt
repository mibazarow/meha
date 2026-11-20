package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.data.tools.person.PersonReadSupport
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_SPEAKER_LIMIT = 50
private const val MAX_SPEAKER_LIMIT = 200

/** Human-readable name of a speaker: its own name, «Я» for the owner, else a stable «Говорящий #id». */
private fun speakerDisplayName(speaker: Speaker): String =
    speaker.name?.takeIf { it.isNotBlank() }
        ?: if (speaker.isSelf) "Я" else "Говорящий #${speaker.id}"

/**
 * `speaker_registry_list`: the speaker registry built from the user's recordings — id, name, person
 * link, voice-sample count and last update. Read-only; an empty registry is reported honestly.
 */
internal class SpeakerRegistryListToolHandler(
    private val speakerRepository: SpeakerRepository,
    private val personRepository: PersonRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "speaker_registry_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val limit = args.int(arguments, "limit", DEFAULT_SPEAKER_LIMIT)
                .coerceIn(1, MAX_SPEAKER_LIMIT)
            val speakers = speakerRepository.list().take(limit)
            if (speakers.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    "Реестр говорящих пуст: пока не обнаружено ни одного голоса. Он заполняется по " +
                        "мере диаризации записей, после чего сюда можно привязать имена и людей.",
                )
            }
            val personNames = speakers.mapNotNull { it.personId }.distinct()
                .mapNotNull { id -> personRepository.get(id)?.let { id to it.displayName } }
                .toMap()
            ToolResult(true, render(speakers, personNames))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения реестра говорящих: ${e.message}")
        }
    }

    private fun render(speakers: List<Speaker>, personNames: Map<Long, String>): String = buildString {
        append("Реестр говорящих (")
        append(speakers.size)
        appendLine("):")
        speakers.forEach { speaker ->
            append("- #")
            append(speaker.id)
            append(' ')
            append(speakerDisplayName(speaker))
            append(" — человек: ")
            append(personText(speaker.personId, personNames))
            append(" — сэмплов: ")
            append(speaker.sampleCount)
            append(" — обновлён ")
            appendLine(formatTimestamp(speaker.updatedAt))
        }
        append(
            "Честно: это голосовые кластеры из ваших записей. Имя и привязку меняет " +
                "speaker_name_assign, удаление — speaker_registry_forget."
        )
    }

    private fun personText(personId: Long?, personNames: Map<Long, String>): String = when {
        personId == null -> "не привязан"
        personNames[personId] != null -> "#$personId ${personNames[personId]}"
        else -> "#$personId (персона не найдена)"
    }
}

/**
 * `speaker_name_assign`: gives a speaker a name, links it to a person of the personal base, or marks
 * it as the device owner («это я»). The person is resolved WITHOUT guessing — an ambiguous match
 * returns the candidate list and changes nothing, so a partially applied update is impossible.
 */
internal class SpeakerNameAssignToolHandler(
    private val speakerRepository: SpeakerRepository,
    private val personRepository: PersonRepository,
    private val readSupport: PersonReadSupport,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "speaker_name_assign"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = args.long(arguments, "speaker_id")
                ?: return@withContext ToolResult(
                    false,
                    "Укажите speaker_id (числовой id говорящего).",
                )
            val speaker = speakerRepository.getById(id)
                ?: return@withContext ToolResult(false, "Говорящий #$id не найден в реестре.")
            val name = args.string(arguments, "name")
            val personQuery = args.string(arguments, "person")
            val self = args.boolOrNull(arguments, "self")
            if (name == null && personQuery == null && self == null) {
                return@withContext ToolResult(
                    false,
                    "Нечего менять: укажите name, person и/или self.",
                )
            }

            // Resolve the person BEFORE any write, so an unknown/ambiguous person leaves the speaker
            // exactly as it was (never a half-applied update).
            val person = if (personQuery != null) {
                when (val resolution = resolvePerson(personQuery)) {
                    is PersonResolution.Found -> resolution.person
                    is PersonResolution.Report -> return@withContext resolution.result
                }
            } else {
                null
            }

            val applied = ArrayList<String>()
            if (name != null) {
                speakerRepository.setName(id, name)
                applied += "имя «$name»"
            }
            if (person != null) {
                speakerRepository.linkToPerson(id, person.id)
                applied += "человек #${person.id} ${person.displayName}"
            }
            if (self != null) {
                speakerRepository.upsert(speaker.copy(isSelf = self))
                applied += if (self) "отмечен как «это я»" else "снята отметка «это я»"
            }
            ToolResult(
                true,
                "Говорящий #$id (${speakerDisplayName(speaker)}) обновлён: " +
                    applied.joinToString("; ") + ".",
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка изменения говорящего: ${e.message}")
        }
    }

    /** Outcome of person resolution: a concrete person or an honest report to return as-is. */
    private sealed interface PersonResolution {
        data class Found(val person: Person) : PersonResolution
        data class Report(val result: ToolResult) : PersonResolution
    }

    /** A numeric value is a person id; otherwise the shared honest resolver runs (never a guess). */
    private suspend fun resolvePerson(raw: String): PersonResolution {
        val id = raw.toLongOrNull()
        if (id != null) {
            val person = personRepository.get(id)
            return if (person != null) {
                PersonResolution.Found(person)
            } else {
                PersonResolution.Report(ToolResult(false, "Человек #$id не найден."))
            }
        }
        val result = readSupport.resolveQuery(raw, null)
        return when (result.outcome) {
            PersonResolver.ResolveOutcome.RESOLVED ->
                result.best?.person?.let { PersonResolution.Found(it) }
                    ?: PersonResolution.Report(
                        ToolResult(false, "Не удалось однозначно определить человека по «$raw».")
                    )
            PersonResolver.ResolveOutcome.AMBIGUOUS ->
                PersonResolution.Report(ToolResult(true, readSupport.formatResolve(raw, null, result)))
            PersonResolver.ResolveOutcome.NOT_FOUND ->
                PersonResolution.Report(ToolResult(true, readSupport.notFoundText(raw)))
        }
    }
}

/**
 * `speaker_registry_forget` (dangerous): deletes a speaker AND its voice embeddings, reporting
 * exactly what was removed. Persons and recordings are left untouched.
 */
internal class SpeakerRegistryForgetToolHandler(
    private val speakerRepository: SpeakerRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "speaker_registry_forget"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = args.long(arguments, "speaker_id")
                ?: return@withContext ToolResult(
                    false,
                    "Укажите speaker_id (числовой id говорящего).",
                )
            val speaker = speakerRepository.getById(id)
                ?: return@withContext ToolResult(false, "Говорящий #$id не найден в реестре.")
            val embeddings = speakerRepository.listEmbeddings(id).size
            val removedEmbeddings = speakerRepository.deleteEmbeddingsForSpeaker(id)
            val removedRows = speakerRepository.delete(id)
            ToolResult(
                true,
                "Говорящий #$id (${speakerDisplayName(speaker)}) удалён: голосовых сэмплов удалено " +
                    "$removedEmbeddings (было $embeddings), строк реестра $removedRows. Действие " +
                    "необратимо; персоны и записи не затронуты.",
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка удаления говорящего: ${e.message}")
        }
    }
}
