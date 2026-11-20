package com.andharness.ru.data.tools.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Reminders
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_reminders_set`: adds a reminder to an event and verifies the row was written.
 *
 * Reference: `CalendarContract.Reminders` (EVENT_ID, MINUTES, METHOD required on insert) —
 * https://developer.android.com/reference/android/provider/CalendarContract.Reminders
 */
internal class CalendarRemindersSetToolHandler(
    private val context: Context,
    private val support: CalendarSupport,
) : ToolHandler {

    override val name = "calendar_reminders_set"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val eventId = (arguments["event_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите числовой event_id.")
            val minutesArg = (arguments["minutes"] as? JsonPrimitive)?.content?.trim()
            val minutes = minutesArg?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Укажите minutes (число минут до начала, можно отрицательное).")

            // Reminders.MINUTES is minutes before the event start (negative = after start);
            // METHOD is required on insert.
            // https://developer.android.com/reference/android/provider/CalendarContract.Reminders
            val values = ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(Reminders.CONTENT_URI, values)
                ?: return@withContext ToolResult(false, "Не удалось добавить напоминание.")

            if (support.eventHasReminder(eventId, minutes)) {
                ToolResult(true, "Напоминание за $minutes мин добавлено к событию id=$eventId.")
            } else {
                ToolResult(false, "Напоминание не подтверждено после записи (event_id=$eventId).")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение календаря (нужен WRITE_CALENDAR).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
