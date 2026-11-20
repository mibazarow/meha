package com.andharness.ru.data.tools.communications

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_SEARCH_LIMIT = 20
private const val MAX_SEARCH_LIMIT = 100

/**
 * `transcript_search`: FTS search over already-transcribed segment texts. Returns the recording id,
 * the timecode and the matched text for each hit, grouped per recording and ordered by time.
 */
internal class TranscriptSearchToolHandler(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "transcript_search"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = args.string(arguments, "query")
                ?: return@withContext ToolResult(false, "Укажите query для поиска по расшифровкам.")
            val limit = args.int(arguments, "limit", DEFAULT_SEARCH_LIMIT)
                .coerceIn(1, MAX_SEARCH_LIMIT)

            val ids = transcriptRepository.searchFts(query, limit)
            val segments = transcriptRepository.segmentsByIds(ids)
            if (segments.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    "По запросу «$query» в расшифровках ничего не найдено " +
                        "(ищутся только уже распознанные записи)."
                )
            }

            val recordings = segments.map { it.recordingId }.distinct()
                .mapNotNull { id -> recordingsRepository.getById(id)?.let { id to it } }
                .toMap()

            ToolResult(true, render(query, segments, recordings))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка поиска по расшифровкам: ${e.message}")
        }
    }

    private fun render(
        query: String,
        segments: List<TranscriptSegment>,
        recordings: Map<Long, Recording>,
    ): String = buildString {
        val ordered = segments.sortedWith(
            compareBy<TranscriptSegment> { it.recordingId }.thenBy { it.startMs }.thenBy { it.id }
        )
        append("Совпадения в расшифровках по «")
        append(query)
        append("» (")
        append(ordered.size)
        appendLine("):")
        ordered.forEach { segment ->
            val recording = recordings[segment.recordingId]
            val name = recording?.displayName?.takeIf { it.isNotBlank() } ?: "запись #${segment.recordingId}"
            append("  - recording #")
            append(segment.recordingId)
            append(" «")
            append(name)
            append("» [")
            append(formatTimecode(segment.startMs))
            append('-')
            append(formatTimecode(segment.endMs))
            append("]: ")
            appendLine(segment.text)
        }
        append("Честно: поиск идёт по проиндексированным текстам расшифровок (FTS), не по аудио.")
    }
}
