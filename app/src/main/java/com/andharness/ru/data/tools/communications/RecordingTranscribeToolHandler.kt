package com.andharness.ru.data.tools.communications

import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.tools.formatDuration
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.communications.RecordingsRepository
import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val LANGUAGE_AUTO = "auto"

/**
 * `recording_transcribe`: runs the real whisper.cpp pipeline through [TranscriptionService] and
 * reports the TRUE outcome. It never downloads a model and never fabricates success: a missing model
 * (or missing audio, or a recogniser error) is returned as an honest failure with the real reason.
 */
internal class RecordingTranscribeToolHandler(
    private val recordingsRepository: RecordingsRepository,
    private val transcriptionService: TranscriptionService,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "recording_transcribe"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = args.long(arguments, "id")
                ?: return@withContext ToolResult(false, "Укажите id записи (recording_transcribe).")
            val language = args.string(arguments, "language") ?: LANGUAGE_AUTO
            val rawModel = args.string(arguments, "model")
            val model = when {
                rawModel == null -> SpeechModel.DEFAULT
                rawModel.equals("base", ignoreCase = true) -> SpeechModel.WHISPER_BASE
                rawModel.equals("small", ignoreCase = true) -> SpeechModel.WHISPER_SMALL
                else -> return@withContext ToolResult(
                    false,
                    "Неизвестная модель «$rawModel». Доступно: base, small."
                )
            }

            val recording = recordingsRepository.getById(id)
                ?: return@withContext ToolResult(false, "Запись #$id не найдена.")

            transcriptionService.transcribeRecording(id, model, language).fold(
                onSuccess = { outcome ->
                    val name = recording.displayName?.takeIf { it.isNotBlank() } ?: "без имени"
                    val segments = if (outcome.segmentCount == 0L) {
                        "0 (возможно, в записи нет речи)"
                    } else {
                        outcome.segmentCount.toString()
                    }
                    ToolResult(
                        true,
                        "Расшифровка записи #$id «$name» готова. Сегментов: $segments. " +
                            "Длительность: ${formatDuration(outcome.durationMs)}. " +
                            "Модель: ${model.displayName}. Язык: ${outcome.language ?: LANGUAGE_AUTO}."
                    )
                },
                onFailure = { error ->
                    ToolResult(
                        false,
                        "Расшифровка записи #$id не удалась: " +
                            (error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка") +
                            ". Проверь, что модель (${model.displayName}) скачана и аудиофайл доступен."
                    )
                },
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка запуска расшифровки: ${e.message}")
        }
    }
}
