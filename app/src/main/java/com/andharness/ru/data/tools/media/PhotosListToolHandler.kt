package com.andharness.ru.data.tools.media

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.data.tools.parseLimit
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `photos_list`: lists image entries from MediaStore. */
internal class PhotosListToolHandler(private val context: Context) : ToolHandler {
    override val name = "photos_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val query = ((arguments["query"] as? JsonPrimitive)?.content ?: "").trim()
            val limit = parseLimit(arguments["limit"] as? JsonPrimitive)

            val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
            )
            val selection = if (query.isEmpty()) null else {
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?"
            }
            val selectionArgs = if (query.isEmpty()) null else arrayOf("%$query%")
            val sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC"

            val resolver = context.contentResolver
            val lines = ArrayList<String>()
            resolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.Images.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                while (c.moveToNext() && lines.size < limit) {
                    val id = c.getString(idIdx)
                    val name = c.getString(nameIdx)
                    val size = c.getString(sizeIdx)
                    lines.add("$id | $name | $size")
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "Фото не найдены.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "photos_list failed", e)
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
