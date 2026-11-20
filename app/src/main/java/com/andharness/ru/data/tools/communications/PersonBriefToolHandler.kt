package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.tools.person.PersonReadSupport
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.communications.PersonBriefComposer
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_BRIEF_DAYS = 30
private const val MAX_BRIEF_DAYS = 365

private const val FETCH_INTERACTIONS = 60
private const val FETCH_RECORDINGS = 40
private const val FETCH_MOMENTS = 60
private const val MAX_SEGMENTS_PER_RECORDING = 10

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * `person_brief`: a bounded, honest summary of a person over a time window — recent interactions,
 * transcript moments from the person's recordings, and linked memories/opinions.
 *
 * The person is resolved without guessing: a numeric argument is an id, otherwise the shared
 * [PersonReadSupport]/[PersonResolver] path is used and an ambiguous match returns the candidate
 * list instead of a brief.
 */
internal class PersonBriefToolHandler(
    private val readSupport: PersonReadSupport,
    private val personRepository: PersonRepository,
    private val interactionRepository: InteractionRepository,
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val assistRepository: AssistRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "person_brief"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rawPerson = args.string(arguments, "person")
                ?: return@withContext ToolResult(false, "Укажите person: имя или числовой id.")
            val days = args.int(arguments, "days", DEFAULT_BRIEF_DAYS).coerceIn(1, MAX_BRIEF_DAYS)

            val person = when (val resolution = resolvePerson(rawPerson)) {
                is Resolution.Found -> resolution.person
                is Resolution.Report -> return@withContext resolution.result
            }

            val now = System.currentTimeMillis()
            val cutoff = now - days * DAY_MS

            val interactions = interactionRepository.recentFor(person.id, FETCH_INTERACTIONS)
                .filter { it.at >= cutoff }
                .map { interaction ->
                    PersonBriefComposer.InteractionLine(
                        at = interaction.at,
                        channel = interaction.channel,
                        direction = interaction.direction,
                        summary = listOfNotNull(
                            interaction.title?.takeIf { it.isNotBlank() },
                            interaction.summary?.takeIf { it.isNotBlank() },
                        ).joinToString(" — ").ifBlank { null },
                    )
                }

            ToolResult(
                true,
                PersonBriefComposer.render(
                    personName = person.displayName,
                    personId = person.id,
                    days = days,
                    now = now,
                    interactions = interactions,
                    moments = collectMoments(person.id, cutoff),
                    memories = collectMemories(person.displayName, cutoff),
                    opinions = collectOpinions(person.displayName, cutoff),
                ),
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка сборки сводки: ${e.message}")
        }
    }

    /** Outcome of person resolution: either a concrete person or an honest report to return as-is. */
    private sealed interface Resolution {
        data class Found(val person: Person) : Resolution
        data class Report(val result: ToolResult) : Resolution
    }

    /**
     * Resolves [raw] to a person: a numeric value is looked up by id, otherwise the shared resolver
     * runs. An ambiguous/not-found answer is returned as a [Resolution.Report] (never a guess).
     */
    private suspend fun resolvePerson(raw: String): Resolution {
        val id = raw.toLongOrNull()
        if (id != null) {
            val person = personRepository.get(id)
            return if (person != null) {
                Resolution.Found(person)
            } else {
                Resolution.Report(ToolResult(false, "Человек #$id не найден."))
            }
        }
        val result = readSupport.resolveQuery(raw, null)
        return when (result.outcome) {
            PersonResolver.ResolveOutcome.RESOLVED ->
                result.best?.person?.let { Resolution.Found(it) }
                    ?: Resolution.Report(
                        ToolResult(false, "Не удалось однозначно определить человека по «$raw».")
                    )
            PersonResolver.ResolveOutcome.AMBIGUOUS ->
                Resolution.Report(ToolResult(true, readSupport.formatResolve(raw, null, result)))
            PersonResolver.ResolveOutcome.NOT_FOUND ->
                Resolution.Report(ToolResult(true, readSupport.notFoundText(raw)))
        }
    }

    /** Transcript moments of the person's recordings, bounded by [cutoff], capped and ordered. */
    private suspend fun collectMoments(
        personId: Long,
        cutoff: Long,
    ): List<PersonBriefComposer.TranscriptMoment> {
        val out = ArrayList<PersonBriefComposer.TranscriptMoment>()
        for (recording in recordingsRepository.recentForPerson(personId, FETCH_RECORDINGS)) {
            if (recording.createdAt < cutoff) continue
            val label = recording.displayName?.takeIf { it.isNotBlank() } ?: "запись #${recording.id}"
            val segments = transcriptRepository.segmentsFor(recording.id)
            for (segment in segments.take(MAX_SEGMENTS_PER_RECORDING)) {
                out += PersonBriefComposer.TranscriptMoment(
                    at = recording.createdAt,
                    recordingLabel = label,
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text,
                )
                if (out.size >= FETCH_MOMENTS) return out
            }
        }
        return out
    }

    /** Best-effort memories about the person; a recall failure yields no memories, not a failure. */
    private suspend fun collectMemories(
        personName: String,
        cutoff: Long,
    ): List<PersonBriefComposer.DatedText> = runCatching {
        assistRepository.searchMemoriesRanked(personName, FETCH_MOMENTS)
            .filter { it.lexicallyMatched && it.note.createdAt >= cutoff }
            .map { PersonBriefComposer.DatedText(it.note.createdAt, it.note.text) }
    }.getOrDefault(emptyList())

    /** Best-effort opinions that mention the person; a failure yields none. */
    private suspend fun collectOpinions(
        personName: String,
        cutoff: Long,
    ): List<PersonBriefComposer.DatedText> = runCatching {
        assistRepository.listOpinions(FETCH_MOMENTS)
            .filter { opinion ->
                opinion.createdAt >= cutoff &&
                    (opinion.text.contains(personName, ignoreCase = true) ||
                        opinion.tags.contains(personName, ignoreCase = true))
            }
            .map { PersonBriefComposer.DatedText(it.createdAt, it.text) }
    }.getOrDefault(emptyList())
}
