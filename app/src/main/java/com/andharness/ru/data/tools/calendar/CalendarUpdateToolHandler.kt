package com.andharness.ru.data.tools.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_update`: updates an event's title / start / end and optionally replaces its reminder.
 *
 * Reference: `CalendarContract.Events` —
 * https://developer.android.com/reference/android/provider/CalendarContract.Events
 */
internal class CalendarUpdateToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_update"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = (arguments["id"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите числовой id события.")
            val values = ContentValues()
            (arguments["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?.let { values.put(Events.TITLE, it) }
            (arguments["start"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?.let { values.put(Events.DTSTART, CalendarTimeParser.parse(it)) }
            (arguments["end"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?.let { values.put(Events.DTEND, CalendarTimeParser.parse(it, endOfDay = true)) }
            if (values.size() == 0) {
                return@withContext ToolResult(false, "Нечего обновлять: укажите title, start и/или end.")
            }
            val updated = context.contentResolver.update(
                Events.CONTENT_URI,
                values,
                "${Events._ID} = ?",
                arrayOf(id.toString()),
            )
            if (updated <= 0) {
                return@withContext ToolResult(false, "Событие id=$id не найдено.")
            }
            // Optional reminder: replace existing reminders for the event.
            val reminderArg = (arguments["reminder"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (reminderArg != null) {
                context.contentResolver.delete(
                    Reminders.CONTENT_URI,
                    "${Reminders.EVENT_ID} = ?",
                    arrayOf(id.toString()),
                )
                val reminderValues = ContentValues().apply {
                    put(Reminders.MINUTES, reminderArg)
                    put(Reminders.EVENT_ID, id)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues)
            }
            ToolResult(true, "Событие id=$id обновлено.")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
