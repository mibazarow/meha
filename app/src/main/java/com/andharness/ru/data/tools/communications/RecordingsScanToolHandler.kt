package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.communications.RecordingsScanner
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_SCAN_LIMIT = 200
private const val MAX_SCAN_LIMIT = 1000

/**
 * `recordings_scan`: enumerates the device's audio files through `MediaStore.Audio` and registers the
 * ones that are not already tracked. Reports the REAL counters returned by [RecordingsScanner]
 * (found / added / skipped) and never fabricates a number.
 */
internal class RecordingsScanToolHandler(
    private val scanner: RecordingsScanner,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "recordings_scan"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val limit = args.int(arguments, "limit", DEFAULT_SCAN_LIMIT)
                .coerceIn(1, MAX_SCAN_LIMIT)
            scanner.scan(limit).fold(
                onSuccess = { result ->
                    ToolResult(
                        true,
                        "Скан записей завершён (лимит $limit). Найдено: ${result.found}. " +
                            "Добавлено: ${result.added}. Пропущено (уже зарегистрированы или " +
                            "недоступны): ${result.skipped}.",
                    )
                },
                onFailure = { error ->
                    ToolResult(
                        false,
                        "Скан записей не удался: " +
                            (error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка") +
                            ". Проверь, что выдано разрешение на доступ к аудио.",
                    )
                },
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка сканирования записей: ${e.message}")
        }
    }
}
