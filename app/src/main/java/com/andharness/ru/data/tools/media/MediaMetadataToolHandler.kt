package com.andharness.ru.data.tools.media

import android.media.MediaMetadataRetriever
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `media_metadata`: reports the media tags of an audio/video file. */
internal class MediaMetadataToolHandler(private val io: MediaPathIo) : ToolHandler {
    override val name = "media_metadata"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            val raw = (arguments["path"] as? JsonPrimitive)?.content.orEmpty()
            val file = try {
                io.resolveMediaPath(raw)
            } catch (e: IllegalArgumentException) {
                return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
            }
            if (!file.exists()) {
                return@withContext ToolResult(false, "Файл не найден: «${file.name}».")
            }
            if (!file.isFile) {
                return@withContext ToolResult(false, "Не файл: «${file.name}».")
            }

            // setDataSource(String) + extractMetadata(int) read the media tags.
            // References: MediaMetadataRetriever —
            // https://developer.android.com/reference/android/media/MediaMetadataRetriever
            retriever.setDataSource(file.absolutePath)
            val lines = ArrayList<String>()

            fun add(label: String, key: Int) {
                val value = retriever.extractMetadata(key)
                if (!value.isNullOrBlank()) lines.add("$label: $value")
            }

            add("Заголовок", MediaMetadataRetriever.METADATA_KEY_TITLE)
            add("Исполнитель", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            add("Альбом", MediaMetadataRetriever.METADATA_KEY_ALBUM)
            add("Длительность (мс)", MediaMetadataRetriever.METADATA_KEY_DURATION)
            add("Ширина", MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            add("Высота", MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            add("Поворот (°)", MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            add("Битрейт", MediaMetadataRetriever.METADATA_KEY_BITRATE)
            add("MIME-тип", MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            if (lines.isEmpty()) {
                ToolResult(true, "Метаданные не найдены: «${file.name}» (файл открыт, но тегов нет).")
            } else {
                ToolResult(true, (listOf("Метаданные «${file.name}»:") + lines).joinToString("\n"))
            }
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_metadata failed", e)
            ToolResult(false, "Не удалось прочитать метаданные: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }
    }
}
