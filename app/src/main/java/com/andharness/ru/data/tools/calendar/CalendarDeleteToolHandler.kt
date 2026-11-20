package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.provider.CalendarContract.Events
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_delete`: deletes an event by id, honestly reporting when nothing was deleted.
 *
 * Reference: `CalendarContract.Events` —
 * https://developer.android.com/reference/android/provider/CalendarContract.Events
 */
internal class CalendarDeleteToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_delete"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = (arguments["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите числовой id события.")
            val deleted = context.contentResolver.delete(
                Events.CONTENT_URI,
                "${Events._ID} = ?",
                arrayOf(id.toString()),
            )
            // Post-check (BUG-059): success only when the event is really gone from the provider.
            val gone = !eventExists(id)
            if (deleted > 0 && gone) {
                ToolResult(true, "Событие id=$id удалено.")
            } else if (deleted > 0) {
                ToolResult(false, "Событие id=$id всё ещё видно после удаления — НЕ подтверждено.")
            } else {
                ToolResult(false, "Событие id=$id не найдено.")
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /** Whether an event row with [id] still resolves (honest read-back after a delete). */
    private fun eventExists(id: Long): Boolean {
        var exists = false
        context.contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID),
            "${Events._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { c -> exists = c.moveToFirst() }
        return exists
    }
}
