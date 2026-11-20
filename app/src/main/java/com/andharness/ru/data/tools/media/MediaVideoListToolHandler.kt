package com.andharness.ru.data.tools.media

import android.content.Context
import android.provider.MediaStore
import com.andharness.ru.data.tools.parseLimit
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `media_video_list`: lists video entries from MediaStore. */
internal class MediaVideoListToolHandler(private val context: Context) : ToolHandler {
    override val name = "media_video_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = ((arguments["query"] as? JsonPrimitive)?.content ?: "").trim()
            val limit = parseLimit(arguments["limit"] as? JsonPrimitive)
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATA,
            )
            val selection = if (query.isEmpty()) null else MediaStore.Video.Media.TITLE + " LIKE ?"
            val selectionArgs = if (query.isEmpty()) null else arrayOf("%$query%")
            val lines = ArrayList<String>()
            context.contentResolver.query(
                uri, projection, selection, selectionArgs,
                MediaStore.Video.Media.TITLE + " ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Video.Media._ID)
                val titleIdx = c.getColumnIndex(MediaStore.Video.Media.TITLE)
                val durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val pathIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                while (c.moveToNext() && lines.size < limit) {
                    lines.add(
                        "${c.getString(idIdx)} | ${c.getString(titleIdx)} | " +
                            "${c.getString(durIdx)}мс | ${c.getString(pathIdx)}"
                    )
                }
            }
            if (lines.isEmpty()) ToolResult(true, "Видео не найдено.") else ToolResult(true, lines.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
