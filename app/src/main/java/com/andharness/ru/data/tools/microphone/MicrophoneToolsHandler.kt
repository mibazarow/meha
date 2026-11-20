package com.andharness.ru.data.tools.microphone

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.media.MediaScanRegistration
import com.andharness.ru.data.tools.newMediaRecorder
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Builds one [ToolHandler] for [audio_record]: records audio with [MediaRecorder]
 * for a fixed duration and writes an .m4a (AAC in the MPEG-4 container) file.
 *
 * The optional `destination` is resolved inside the allowed storage roots; when it is omitted the
 * recording goes to a generated `Music/Meha/audio_<timestamp>.m4a` path. A missing extension
 * on an explicit `destination` is filled in with `.m4a` (the audio-only MPEG-4 extension). The
 * finished, non-empty file is registered in the system MediaStore via [MediaScanRegistration] so it
 * appears in the device Gallery; a scan problem is reported in the message but never fails the call.
 *
 * References:
 * - `MediaRecorder` setup sequence — `setAudioSource`, `setOutputFormat`,
 *   `setAudioEncoder`, `setOutputFile`, `prepare`, `start`, `stop`, `release`:
 *   https://developer.android.com/media/platform/mediarecorder
 *   https://developer.android.com/reference/android/media/MediaRecorder
 * - `MediaRecorder.AudioSource.MIC`:
 *   https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
 * - `MediaRecorder.OutputFormat.MPEG_4` / `MediaRecorder.AudioEncoder.AAC`:
 *   https://developer.android.com/reference/android/media/MediaRecorder.OutputFormat
 *   https://developer.android.com/reference/android/media/MediaRecorder.AudioEncoder
 * - Recorded audio becomes visible in the Gallery through the MediaStore audio collection
 *   (`MediaStore.Audio.Media`) — https://developer.android.com/training/data-storage/shared/media
 */
class MicrophoneToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "audio_record"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val durationArg = (arguments["duration"] as? JsonPrimitive)?.content?.toLongOrNull()
                val duration = durationArg?.coerceAtLeast(1) ?: 10

                // Optional destination: explicit path, otherwise a generated Music/Meha name.
                val destinationArg = (arguments["destination"] as? JsonPrimitive)
                    ?.content?.trim().orEmpty()
                val raw = destinationArg.ifEmpty {
                    "Music/Meha/audio_${System.currentTimeMillis()}.m4a"
                }

                val resolved = try {
                    resolveSafePath(context.allowedToolRoots(), raw)
                } catch (e: IllegalArgumentException) {
                    return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
                }
                // MPEG-4/AAC container: fill in the audio-only .m4a extension when none is present.
                val file = if (resolved.name.contains('.')) {
                    resolved
                } else {
                    File(resolved.parentFile, resolved.name + ".m4a")
                }
                file.parentFile?.mkdirs()

                val recorder = newMediaRecorder(context)
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setOutputFile(file.absolutePath)
                    recorder.prepare()
                    recorder.start()
                    delay(duration * 1000L)
                    recorder.stop()
                } catch (e: Exception) {
                    try { recorder.release() } catch (e: Exception) { Log.w(TAG, "MediaRecorder release failed", e) }
                    return@withContext ToolResult(false, "Ошибка записи: ${e.message}")
                } finally {
                    try { recorder.release() } catch (e: Exception) { Log.w(TAG, "MediaRecorder release failed", e) }
                }
                // Honest post-check: a successful stop() must leave a non-empty file behind.
                if (!file.exists() || file.length() <= 0L) {
                    return@withContext ToolResult(
                        false,
                        "Запись не удалась: файл не создан или пуст (${file.absolutePath}).",
                    )
                }
                // MediaStore registration never throws and never fails a successful recording.
                val scan = MediaScanRegistration.register(context, file)
                ToolResult(
                    true,
                    "Запись сохранена: ${file.absolutePath} (${duration}с) ${scan.galleryNote}",
                )
            }
        },
    )

    private companion object {
        private const val TAG = "Andharness"
    }
}
