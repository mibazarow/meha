package com.andharness.ru.data.tools.communications

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Label used when a segment has no attributed speaker yet (diarisation not run). */
private const val NO_SPEAKER = "—"

/**
 * `transcript_get`: renders one recording's transcript as `[start-end] speaker: text` lines. The
 * speaker is resolved honestly: «Я» for the device owner, the person's name when a speaker is linked
 * to a person, an explicit speaker name, else «Говорящий N»; a segment with no speaker is rendered
 * as [NO_SPEAKER] rather than guessed.
 */
internal class TranscriptGetToolHandler(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val speakerRepository: SpeakerRepository,
    private val personRepository: PersonRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "transcript_get"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val recordingId = args.long(arguments, "recording_id")
                ?: return@withContext ToolResult(false, "Укажите recording_id (transcript_get).")
            val recording = recordingsRepository.getById(recordingId)
                ?: return@withContext ToolResult(false, "Запись #$recordingId не найдена.")
            val segments = transcriptRepository.segmentsFor(recordingId)

            val name = recording.displayName?.takeIf { it.isNotBlank() } ?: "без имени"
            if (segments.isEmpty()) {
                val status = recording.transcriptStatus.name
                val reason = recording.errorText?.takeIf { it.isNotBlank() }?.let { " (ошибка: $it)" }.orEmpty()
                return@withContext ToolResult(
                    true,
                    "У записи #$recordingId «$name» расшифровки нет (статус $status$reason). " +
                        "Запусти recording_transcribe, чтобы распознать её."
                )
            }

            val labels = resolveSpeakerLabels(segments)
            ToolResult(true, render(recordingId, name, segments, labels))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения расшифровки: ${e.message}")
        }
    }

    /** Maps each attributed speaker id to its honest label. */
    private suspend fun resolveSpeakerLabels(
        segments: List<TranscriptSegment>,
    ): Map<Long, String> {
        val speakerIds = segments.mapNotNull { it.speakerId }.distinct()
        if (speakerIds.isEmpty()) return emptyMap()

        val speakers = speakerIds.mapNotNull { id -> speakerRepository.getById(id)?.let { id to it } }.toMap()
        val personIds = speakers.values.mapNotNull { it.personId }.distinct()
        val persons = personIds.mapNotNull { id -> personRepository.get(id)?.let { id to it.displayName } }.toMap()

        val labels = HashMap<Long, String>(speakers.size)
        speakers.forEach { (id, speaker) -> labels[id] = labelFor(speaker, persons) }
        return labels
    }

    private fun labelFor(speaker: Speaker, persons: Map<Long, String>): String = when {
        speaker.isSelf -> "Я"
        !speaker.name.isNullOrBlank() -> speaker.name.trim()
        speaker.personId != null -> persons[speaker.personId] ?: "Говорящий ${speaker.id}"
        else -> "Говорящий ${speaker.id}"
    }

    private fun render(
        recordingId: Long,
        name: String,
        segments: List<TranscriptSegment>,
        labels: Map<Long, String>,
    ): String = buildString {
        append("Расшифровка записи #")
        append(recordingId)
        append(" «")
        append(name)
        append("», сегментов ")
        append(segments.size)
        appendLine(":")
        segments.forEach { segment ->
            append("  [")
            append(formatTimecode(segment.startMs))
            append('-')
            append(formatTimecode(segment.endMs))
            append("] ")
            append(segment.speakerId?.let { labels[it] } ?: NO_SPEAKER)
            append(": ")
            appendLine(segment.text)
        }
        append(
            "Честно: «—» означает, что говорящий ещё не определён (диаризация не выполнена); " +
                "таймкоды — от начала записи."
        )
    }
}
