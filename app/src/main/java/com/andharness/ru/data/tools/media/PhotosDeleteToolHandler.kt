package com.andharness.ru.data.tools.media

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `photos_delete`: deletes image entries from MediaStore by id. */
internal class PhotosDeleteToolHandler(private val context: Context) : ToolHandler {
    override val name = "photos_delete"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val idsArg = arguments["ids"] as? JsonPrimitive
            val ids = idsArg?.content?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
            if (ids.isEmpty()) {
                return@withContext ToolResult(false, "Укажите ids.")
            }
            if (!Environment.isExternalStorageManager()) {
                return@withContext ToolResult(false, "Нужен доступ ко всем файлам (MANAGE_EXTERNAL_STORAGE).")
            }
            val uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val deleted = ids.count { id ->
                val selection = MediaStore.Images.Media._ID + " = ?"
                val args = arrayOf(id)
                context.contentResolver.delete(uri, selection, args) > 0
            }
            if (deleted == 0) {
                ToolResult(false, "Не удалось удалить ни одного фото (проверьте ids).")
            } else {
                ToolResult(true, "Удалено $deleted фото")
            }
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "photos_delete failed", e)
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
