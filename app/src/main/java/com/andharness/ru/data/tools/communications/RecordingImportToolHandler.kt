package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.communications.RecordingImporter
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val LANGUAGE_AUTO = "auto"

/**
 * `recording_import`: registers one specific audio file (absolute path) or `content://` URI and,
 * unless `transcribe=false`, immediately runs the whisper.cpp pipeline. Every outcome is the REAL
 * one returned by [RecordingImporter] — a missing model, revoked grant or unreadable file is an
 * honest failure, never a fabricated success.
 */
internal class RecordingImportToolHandler(
    private val importer: RecordingImporter,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "recording_import"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val path = args.string(arguments, "path")
                ?: return@withContext ToolResult(
                    false,
                    "Укажи path — абсолютный путь к аудиофайлу или content:// URI.",
                )
            val transcribe = args.boolOrNull(arguments, "transcribe") ?: true
            val language = args.string(arguments, "language") ?: LANGUAGE_AUTO

            importer.import(path, transcribe, language).fold(
                onSuccess = { outcome ->
                    val name = outcome.displayName?.takeIf { it.isNotBlank() } ?: "без имени"
                    val body = if (outcome.transcribed) {
                        val segments = if (outcome.segmentCount == 0L) {
                            "0 (возможно, в записи нет речи)"
                        } else {
                            outcome.segmentCount.toString()
                        }
                        "Импортировано и расшифровано: id=${outcome.recordingId}, «$name», " +
                            "сегментов: $segments. Язык: $language."
                    } else {
                        "Импортировано: id=${outcome.recordingId}, «$name» (расшифровка не " +
                            "запускалась; вызови recording_transcribe с этим id)."
                    }
                    ToolResult(true, body)
                },
                onFailure = { error ->
                    ToolResult(
                        false,
                        "Импорт не удался: " +
                            (error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка") + ".",
                    )
                },
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка импорта записи: ${e.message}")
        }
    }
}
