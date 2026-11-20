package com.andharness.ru.data.tools.communications

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.communications.CommitmentCandidate
import com.andharness.ru.domain.communications.CommitmentExtractor
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.TimeZone

/**
 * `commitments_extract`: scans the transcript segments of recordings created inside a time window for
 * commitment/agreement-like sentences, using the pure [CommitmentExtractor] heuristic.
 *
 * Read-only unless `create=true`, which persists each CLEAR candidate as a todo through
 * [AssistRepository.addTodo]. It never invents items: only sentences the heuristic actually matched
 * on a cue phrase are created, and the tool reports the exact count. The result is framed as
 * candidates to review — the heuristic is keyword-based, not an LLM, and says so in the output.
 */
internal class CommitmentsExtractToolHandler(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptRepository: TranscriptRepository,
    private val assistRepository: AssistRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "commitments_extract"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val windowRaw = args.string(arguments, "window") ?: CommitmentExtractor.DEFAULT_WINDOW
            val since = CommitmentExtractor.windowStartMillis(windowRaw, now, zoneOffsetMs(now))
                ?: return@withContext ToolResult(
                    false,
                    "Некорректное окно «$windowRaw»: укажи today или Nd (например 7d).",
                )
            val create = args.boolOrNull(arguments, "create") ?: false

            val segments = recentSegments(since)
            val candidates = CommitmentExtractor.extract(segments)
            val created = if (create) {
                candidates.count { assistRepository.addTodo(it.text) > 0L }
            } else {
                0
            }
            ToolResult(
                true,
                formatCommitmentReport(candidates, created, create, windowRaw, segments.size),
            )
        } catch (e: Exception) {
            ToolResult(false, "Не удалось извлечь обещания: ${e.message}")
        }
    }

    /**
     * Segments of recordings whose `createdAt` falls inside the window, newest recordings first.
     * Bounded by [RECORDINGS_SCAN] rows read and [MAX_SEGMENTS] segments collected, so a large store
     * can never make this scan unbounded.
     */
    private suspend fun recentSegments(since: Long): List<TranscriptSegment> {
        val recordings = recordingsRepository.list(limit = RECORDINGS_SCAN)
            .filter { it.createdAt >= since }
            .take(MAX_RECORDINGS)
        val collected = mutableListOf<TranscriptSegment>()
        for (recording in recordings) {
            for (segment in transcriptRepository.segmentsFor(recording.id)) {
                collected += segment
                if (collected.size >= MAX_SEGMENTS) return collected
            }
        }
        return collected
    }

    private fun zoneOffsetMs(nowMs: Long): Long = TimeZone.getDefault().getOffset(nowMs).toLong()

    private companion object {
        /** Newest recordings examined before the window filter is applied. */
        const val RECORDINGS_SCAN = 200

        /** Recordings inside the window whose segments are actually read. */
        const val MAX_RECORDINGS = 100

        /** Hard cap on collected segments. */
        const val MAX_SEGMENTS = 2_000
    }
}

/** Cap on the sentence preview echoed per candidate line. */
private const val COMMITMENT_PREVIEW_CHARS = 160

/**
 * Human report for the extracted candidates and how many were persisted. Pure and JVM-testable:
 * it only formats the values it is given and never claims more than that. [createRequested] is
 * carried so a `create=false` run states plainly that nothing was written.
 */
internal fun formatCommitmentReport(
    candidates: List<CommitmentCandidate>,
    createdCount: Int,
    createRequested: Boolean,
    windowLabel: String,
    scannedSegments: Int,
): String {
    if (candidates.isEmpty()) {
        return "За окно «$windowLabel» фраз-обязательств не найдено (просмотрено сегментов: " +
            "$scannedSegments)."
    }
    val header = buildString {
        append("Найдено кандидатов-обязательств: ${candidates.size} (окно «$windowLabel», ")
        append("просмотрено сегментов: $scannedSegments).")
        if (createRequested) {
            append(" Создано дел: $createdCount.")
        } else {
            append(" create=false — дела не создавались.")
        }
        append(" Это эвристика по ключевым фразам, а не гарантия.")
    }
    val lines = candidates.mapIndexed { index, candidate ->
        val time = formatTimecode(candidate.startMs)
        val confidence = String.format(Locale.ROOT, "%.2f", candidate.confidence)
        val preview = candidate.text.take(COMMITMENT_PREVIEW_CHARS)
        "#${index + 1} | $time | запись #${candidate.recordingId} | «$preview» | " +
            "уверенность $confidence | маркеры: ${candidate.cues.joinToString(", ")}"
    }
    return header + "\n" + lines.joinToString("\n")
}
