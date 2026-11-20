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

/** `media_audio_list`: lists audio entries from MediaStore. */
internal class MediaAudioListToolHandler(private val context: Context) : ToolHandler {
    override val name = "media_audio_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = ((arguments["query"] as? JsonPrimitive)?.content ?: "").trim()
            val limit = parseLimit(arguments["limit"] as? JsonPrimitive)
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
            )
            val selection = if (query.isEmpty()) null else MediaStore.Audio.Media.TITLE + " LIKE ?"
            val selectionArgs = if (query.isEmpty()) null else arrayOf("%$query%")
            val lines = ArrayList<String>()
            context.contentResolver.query(
                uri, projection, selection, selectionArgs,
                MediaStore.Audio.Media.TITLE + " ASC"
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val pathIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext() && lines.size < limit) {
                    lines.add(
                        "${c.getString(idIdx)} | ${c.getString(titleIdx)} | " +
                            "${c.getString(artistIdx)} | ${c.getString(durIdx)}мс | ${c.getString(pathIdx)}"
                    )
                }
            }
            if (lines.isEmpty()) ToolResult(true, "Аудио не найдено.") else ToolResult(true, lines.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
