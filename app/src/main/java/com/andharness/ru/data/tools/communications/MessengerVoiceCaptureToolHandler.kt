package com.andharness.ru.data.tools.communications

import android.content.Context
import com.andharness.ru.bg.AudioCaptureService
import com.andharness.ru.data.communications.RecordingImporter
import com.andharness.ru.data.tools.media.AudioCaptureAppToolHandler
import com.andharness.ru.data.tools.toolRootDir
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.communications.RecordingSourceType
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * `messenger_voice_capture`: records the audio that ANOTHER app is currently playing (a messenger
 * voice message) and imports it as a transcript-backed recording.
 *
 * It does not implement playback capture itself: it delegates to the existing app-audio path
 * ([AudioCaptureAppToolHandler], [AudioCaptureService]) for a bounded `duration_seconds`, then hands
 * the resulting WAV to [RecordingImporter] tagged [RecordingSourceType.MESSENGER_VOICE].
 *
 * **Honesty:** capturing another app's audio requires the user to grant the per-session MediaProjection
 * consent dialog; there is no way to fake it. If the user declines, the tool fails with the real
 * reason. If the source app opted out of playback capture its output is silence — the delegated
 * handler reports the measured level and says so, and that honest note is passed through verbatim.
 *
 * References (developer.android.com):
 * - Playback capture (`AudioPlaybackCaptureConfiguration`) + MediaProjection consent —
 *   https://developer.android.com/media/platform/av-capture
 * - `AudioRecord` (what the service reads from) —
 *   https://developer.android.com/reference/android/media/AudioRecord
 * - `MediaProjectionManager.createScreenCaptureIntent` consent token is single-use on Android 14+ —
 *   https://developer.android.com/reference/android/media/projection/MediaProjectionManager
 */
internal class MessengerVoiceCaptureToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
    private val importer: RecordingImporter,
    private val args: CommunicationsToolArgs,
) : ToolHandler {

    override val name = "messenger_voice_capture"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val seconds = clampCaptureSeconds(args.string(arguments, "duration_seconds"))
            val requestedMs = seconds * SECONDS_TO_MS
            // The capture service has its own hard cap; record the effective duration and say so.
            val effectiveMs = requestedMs.coerceAtMost(AudioCaptureService.MAX_DURATION_MS)
            val cappedNote = if (effectiveMs < requestedMs) {
                " Запрошено ${seconds}с, но служба захвата ограничена " +
                    "${AudioCaptureService.MAX_DURATION_MS / SECONDS_TO_MS}с — записано столько."
            } else {
                ""
            }
            val transcribe = args.boolOrNull(arguments, "transcribe") ?: true
            val language = args.string(arguments, "language") ?: LANGUAGE_AUTO

            val destination = uniqueCaptureFile()
            val captureArgs = buildJsonObject {
                put("path", destination.absolutePath)
                put("duration_ms", effectiveMs.toString())
            }
            // Delegates consent + capture; on failure the output is passed through verbatim so a
            // PERMISSION_REQUIRED marker is not masked.
            val capture = AudioCaptureAppToolHandler(context, uiBridge).execute(captureArgs)
            if (!capture.success) return@withContext capture

            importer.import(
                pathOrUri = destination.absolutePath,
                transcribe = transcribe,
                language = language,
                sourceType = RecordingSourceType.MESSENGER_VOICE,
            ).fold(
                onSuccess = { outcome ->
                    val name = outcome.displayName?.takeIf { it.isNotBlank() } ?: destination.name
                    val transcribeNote = if (outcome.transcribed) {
                        val segments = if (outcome.segmentCount == 0L) {
                            "0 (возможно, речи не было)"
                        } else {
                            outcome.segmentCount.toString()
                        }
                        "Расшифровано, сегментов: $segments."
                    } else {
                        "Расшифровка не запускалась; вызови recording_transcribe с " +
                            "id=${outcome.recordingId}."
                    }
                    ToolResult(
                        true,
                        "${capture.output}$cappedNote Импортировано как голосовое из мессенджера: " +
                            "id=${outcome.recordingId}, «$name». $transcribeNote",
                    )
                },
                onFailure = { error ->
                    ToolResult(
                        false,
                        "${capture.output}$cappedNote Импорт захваченного файла не удался: " +
                            (error.message?.takeIf { it.isNotBlank() } ?: "неизвестная ошибка") + ".",
                    )
                },
            )
        } catch (e: Exception) {
            ToolResult(false, "Не удалось захватить голос из мессенджера: ${e.message}")
        }
    }

    /**
     * A fresh `.wav` path under the app-owned tool root (so it passes `audio_capture_app`'s safe-path
     * check and survives as the recording's source). Timestamped to avoid the overwrite refusal.
     */
    private fun uniqueCaptureFile(): File {
        val dir = File(context.toolRootDir(), CAPTURE_DIR).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val first = File(dir, "messenger-voice-$stamp.wav")
        if (!first.exists()) return first
        for (index in 1..MAX_UNIQUE_ATTEMPTS) {
            val candidate = File(dir, "messenger-voice-$stamp-$index.wav")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "messenger-voice-$stamp-final.wav")
    }

    private companion object {
        const val CAPTURE_DIR = "recordings"
        const val LANGUAGE_AUTO = "auto"
        const val MAX_UNIQUE_ATTEMPTS = 20
        const val SECONDS_TO_MS = 1000L
    }
}

/**
 * `duration_seconds`: default [DEFAULT_CAPTURE_SECONDS], clamped to
 * `[MIN_CAPTURE_SECONDS]..[MAX_CAPTURE_SECONDS]`; a missing/unparsable value yields the default.
 * Pure and JVM-testable.
 */
internal fun clampCaptureSeconds(raw: String?): Long {
    val parsed = raw?.trim()?.toLongOrNull() ?: DEFAULT_CAPTURE_SECONDS
    return parsed.coerceIn(MIN_CAPTURE_SECONDS, MAX_CAPTURE_SECONDS)
}

internal const val DEFAULT_CAPTURE_SECONDS = 30L
internal const val MIN_CAPTURE_SECONDS = 5L
internal const val MAX_CAPTURE_SECONDS = 300L
