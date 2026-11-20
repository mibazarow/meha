package com.andharness.ru.data.tools.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `media_downloads_list`: lists files in the Downloads collection (API 29+). */
internal class MediaDownloadsListToolHandler(private val context: Context) : ToolHandler {
    override val name = "media_downloads_list"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            // MediaStore.Downloads is a special collection added in API 29 (Android 10).
            // Below Q there is no such collection, so the tool reports that honestly
            // instead of crashing on a missing class/field.
            // Reference: https://developer.android.com/reference/android/provider/MediaStore
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@withContext ToolResult(
                    false,
                    "Папка Загрузки (MediaStore.Downloads) доступна только на Android 10 (API 29) или новее.",
                )
            }
            val limit = (arguments["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?.coerceAtLeast(1) ?: DEFAULT_DOWNLOADS_LIMIT

            // Query the Downloads collection: DISPLAY_NAME / SIZE / DATE_ADDED /
            // RELATIVE_PATH are MediaColumns inherited by MediaStore.Downloads.
            // Reference: MediaStore.Downloads.EXTERNAL_CONTENT_URI and MediaColumns —
            // https://developer.android.com/reference/android/provider/MediaStore
            // https://developer.android.com/reference/android/provider/MediaStore.MediaColumns
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.RELATIVE_PATH,
            )
            val lines = ArrayList<String>()
            context.contentResolver.query(
                uri, projection, null, null,
                MediaStore.Downloads.DATE_ADDED + " DESC",
            )?.use { c ->
                val nameIdx = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Downloads.SIZE)
                val dateIdx = c.getColumnIndex(MediaStore.Downloads.DATE_ADDED)
                val pathIdx = c.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
                while (c.moveToNext() && lines.size < limit) {
                    lines.add(
                        "${c.getString(nameIdx)} | ${c.getString(sizeIdx)} байт | " +
                            "${c.getString(pathIdx)} | dateAdded=${c.getString(dateIdx)}",
                    )
                }
            }
            if (lines.isEmpty()) {
                ToolResult(true, "В папке Загрузки нет файлов.")
            } else {
                ToolResult(true, lines.joinToString("\n"))
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет доступа ко всем файлам (нужен MANAGE_EXTERNAL_STORAGE).")
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_downloads_list failed", e)
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        /** `media_downloads_list` default number of returned files when `limit` is omitted. */
        private const val DEFAULT_DOWNLOADS_LIMIT = 100
    }
}
