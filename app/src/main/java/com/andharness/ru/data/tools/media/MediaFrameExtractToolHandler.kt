package com.andharness.ru.data.tools.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream

/** `media_frame_extract`: extracts a video frame at a timestamp and saves it as PNG. */
internal class MediaFrameExtractToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "media_frame_extract"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        try {
            val rawPath = (arguments["path"] as? JsonPrimitive)?.content.orEmpty()
            val input = try {
                io.resolveMediaPath(rawPath)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
            }
            if (!input.exists() || !input.isFile) {
                return@withContext ToolResult(false, "Видеофайл не найден: «${input.name}».")
            }

            val timeRaw = ((arguments["time_ms"] as? JsonPrimitive)?.content ?: "").trim()
            val timeMs = if (timeRaw.isEmpty()) 0L else {
                timeRaw.toLongOrNull()
                    ?: return@withContext ToolResult(false, "Некорректный time_ms: «$timeRaw».")
            }
            if (timeMs < 0) {
                return@withContext ToolResult(false, "time_ms не может быть отрицательным.")
            }

            val rawDest = (arguments["destination"] as? JsonPrimitive)?.content.orEmpty()
            val destination = try {
                io.resolveMediaPath(rawDest)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь назначения.")
            }
            if (destination.exists()) {
                return@withContext ToolResult(
                    false,
                    "Файл назначения уже существует: «${destination.absolutePath}». Перезапись запрещена.",
                )
            }
            val parent = destination.parentFile
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                return@withContext ToolResult(
                    false,
                    "Не удалось создать каталог для «${destination.absolutePath}».",
                )
            }

            retriever.setDataSource(input.absolutePath)
            // getFrameAtTime(timeUs, option) takes MICROSECONDS, hence timeMs * 1000.
            // OPTION_CLOSEST_SYNC returns the nearest sync (key) frame. Reference:
            // https://developer.android.com/reference/android/media/MediaMetadataRetriever#getFrameAtTime(long,%20int)
            val bitmap = retriever.getFrameAtTime(
                timeMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            if (bitmap == null) {
                return@withContext ToolResult(
                    false,
                    "Не удалось извлечь кадр из «${input.name}» (нет видео-дорожки или время вне диапазона).",
                )
            }
            frame = bitmap

            // Atomic write: encode into a same-directory temp file, flush+fsync it, then
            // rename it over the destination (rename on one volume is atomic), so an
            // interrupted write never leaves a half-written PNG behind.
            // References: FileOutputStream / FileDescriptor.sync() / File#renameTo —
            // https://developer.android.com/reference/java/io/FileOutputStream
            // https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
            val temp = File(parent, "${destination.name}.tmp-${System.nanoTime()}")
            try {
                FileOutputStream(temp).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        temp.delete()
                        return@withContext ToolResult(
                            false,
                            "Не удалось закодировать кадр в PNG: «${destination.absolutePath}».",
                        )
                    }
                    out.flush()
                    out.fd.sync()
                }
            } catch (e: Exception) {
                temp.delete()
                return@withContext ToolResult(false, "Не удалось записать файл: ${e.message}")
            }
            if (!temp.renameTo(destination)) {
                temp.delete()
                return@withContext ToolResult(
                    false,
                    "Не удалось атомарно сохранить кадр: «${destination.absolutePath}».",
                )
            }

            // POST-VERIFY: the destination must exist and be non-empty.
            if (!destination.exists() || destination.length() <= 0L) {
                destination.delete()
                return@withContext ToolResult(
                    false,
                    "Кадр не записан (файл пуст или отсутствует): «${destination.absolutePath}».",
                )
            }

            ToolResult(
                true,
                "Кадр сохранён: «${destination.absolutePath}» " +
                    "(${bitmap.width}x${bitmap.height} px, ${destination.length()} байт).",
            )
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_frame_extract failed", e)
            ToolResult(false, "Не удалось извлечь кадр: ${e.message}")
        } finally {
            frame?.recycle()
            runCatching { retriever.release() }
        }
    }
}
