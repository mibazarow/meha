package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.tools.formatDuration
import com.andharness.ru.data.tools.formatTimestamp
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.TranscriptStatus
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_RECORDINGS_LIMIT = 20
private const val MAX_RECORDINGS_LIMIT = 200

/** `recordings_list`: newest-first local recordings with duration and transcript status. */
internal class RecordingsListToolHandler(
    private val recordingsRepository: RecordingsRepository,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "recordings_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val limit = args.int(arguments, "limit", DEFAULT_RECORDINGS_LIMIT)
                .coerceIn(1, MAX_RECORDINGS_LIMIT)
            val rawStatus = args.string(arguments, "status")
            val status = if (rawStatus == null) {
                null
            } else {
                TranscriptStatus.from(rawStatus) ?: return@withContext ToolResult(
                    false,
                    "Неизвестный статус «$rawStatus». Доступно: " +
                        TranscriptStatus.entries.joinToString(", ") { it.name } + "."
                )
            }

            val recordings = recordingsRepository.list(limit, status)
            ToolResult(true, render(recordings, status))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения записей: ${e.message}")
        }
    }

    private fun render(recordings: List<Recording>, status: TranscriptStatus?): String = buildString {
        append("Записи (")
        append(recordings.size)
        if (status != null) {
            append(", статус ")
            append(status.name)
        }
        appendLine(", новые сверху):")
        if (recordings.isEmpty()) {
            appendLine("  нет")
        } else {
            recordings.forEach { recording ->
                append("  - #")
                append(recording.id)
                append(' ')
                append(recording.displayName?.takeIf { it.isNotBlank() } ?: "без имени")
                append(" — ")
                append(formatDuration(recording.durationMs))
                append(" — ")
                append(recording.transcriptStatus.name)
                append(" — ")
                append(formatTimestamp(recording.createdAt))
                append(" — ")
                append(recording.sourceType.name)
                if (!recording.errorText.isNullOrBlank()) {
                    append(" — ошибка: ")
                    append(recording.errorText)
                }
                appendLine()
            }
        }
        append(
            "Честно: показаны только метаданные; аудиофайлы хранятся на диске и в базе не " +
                "дублируются."
        )
    }
}
