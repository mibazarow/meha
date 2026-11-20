package com.andharness.ru.data.tools.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `audio_play`: plays a file with media audio focus, either "fire and forget" or awaited.
 *
 * References (official Android docs):
 * - Audio focus (`AudioFocusRequest`, `requestAudioFocus`, `abandonAudioFocusRequest`, API 26+):
 *   https://developer.android.com/media/optimize/audio-focus
 * - `AudioManager` (`setMode`, `MODE_*`, `setSpeakerphoneOn`):
 *   https://developer.android.com/reference/android/media/AudioManager
 */
internal class AudioPlayToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "audio_play"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (raw.isEmpty()) {
                return@withContext ToolResult(false, "Укажите path — путь к аудиофайлу.")
            }
            val file = try {
                resolveSafePath(context.allowedToolRoots(), raw)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
            }
            if (!file.exists()) {
                return@withContext ToolResult(false, "Файл не найден: «${file.name}».")
            }
            if (!file.isFile) {
                return@withContext ToolResult(false, "Не файл: «${file.name}».")
            }

            val positionRaw = (arguments["position_ms"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val positionMs = if (positionRaw.isEmpty()) {
                0L
            } else {
                positionRaw.toLongOrNull()
                    ?: return@withContext ToolResult(false, "Некорректный position_ms: «$positionRaw».")
            }
            if (positionMs < 0) {
                return@withContext ToolResult(false, "position_ms не может быть отрицательным.")
            }
            val wait = (arguments["wait"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

            val audioManager = context.getSystemService(AudioManager::class.java)
                ?: return@withContext ToolResult(false, "AudioManager недоступен на этом устройстве.")
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            // Audio focus is requested before playback starts so other players duck/pause.
            // https://developer.android.com/media/optimize/audio-focus
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            val focus = try {
                audioManager.requestAudioFocus(focusRequest)
            } catch (e: Exception) {
                Log.e(AudioLog.TAG, "requestAudioFocus failed", e)
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                return@withContext ToolResult(
                    false,
                    "Аудиофокус не получен: другое приложение удерживает звук.",
                )
            }

            // A previous playback (if any) is stopped: this tool is a single-slot player.
            AudioPlaybackHolder.clear()

            val player = createPlayer()
            val completed = CompletableDeferred<Unit>()
            var durationMs = -1L
            try {
                player.setAudioAttributes(attributes)
                player.setDataSource(file.absolutePath)
                player.prepare()
                durationMs = runCatching { player.duration.toLong() }.getOrDefault(-1L)
                player.setOnCompletionListener { completed.complete(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    completed.completeExceptionally(
                        IllegalStateException("плеер сообщил об ошибке (what=$what, extra=$extra)"),
                    )
                    true
                }
                if (positionMs > 0) {
                    player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST)
                }
                player.start()
            } catch (e: Exception) {
                runCatching { player.release() }
                runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
                Log.e(AudioLog.TAG, "audio_play failed", e)
                return@withContext ToolResult(false, "Не удалось воспроизвести файл: ${e.message}")
            }

            AudioPlaybackHolder.hold(player, audioManager, focusRequest, file.absolutePath)

            if (!wait) {
                return@withContext ToolResult(
                    true,
                    "Воспроизведение начато: «${file.name}»" +
                        if (durationMs > 0) " (${durationMs}мс)." else ".",
                )
            }

            val remaining = if (durationMs > 0) durationMs - positionMs else DEFAULT_WAIT_MS
            val budget = remaining.coerceIn(0L, MAX_WAIT_MS) + WAIT_SLACK_MS
            val outcome = withTimeoutOrNull(budget) { runCatching { completed.await() } }
            AudioPlaybackHolder.releaseIfSame(player)
            when {
                outcome == null -> ToolResult(
                    false,
                    "Воспроизведение не завершилось за ${budget / 1000}с — остановлено.",
                )
                outcome.isFailure -> ToolResult(
                    false,
                    "Воспроизведение прервано: ${outcome.exceptionOrNull()?.message}.",
                )
                else -> ToolResult(true, "Воспроизведение завершено: «${file.name}».")
            }
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "audio_play failed", e)
            ToolResult(false, "Не удалось воспроизвести аудио: ${e.message}")
        }
    }

    /** Builds a [MediaPlayer]; the context-aware constructor exists only from API 31. */
    @Suppress("DEPRECATION")
    private fun createPlayer(): MediaPlayer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaPlayer(context)
    } else {
        MediaPlayer()
    }

    private companion object {
        /** `audio_play`: the user must confirm/await a stream; 10 minutes is a hard cap. */
        private const val MAX_WAIT_MS = 600_000L

        /** Grace period on top of the track duration (decoder/teardown slack). */
        private const val WAIT_SLACK_MS = 5_000L

        /** Fallback wait when the player reports no duration. */
        private const val DEFAULT_WAIT_MS = 30_000L
    }
}
