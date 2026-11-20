package com.andharness.ru.data.tools.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoPayload
import com.andharness.ru.domain.undo.UndoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_create`: inserts a one-off event (with an optional reminder) into a writable calendar
 * and records an undo entry.
 *
 * References:
 * - `CalendarContract.Events` / `CalendarContract.Reminders` (EVENT_ID, MINUTES, METHOD required
 *   on insert): https://developer.android.com/reference/android/provider/CalendarContract.Events
 * - `ContentResolver.insert(uri, values)`:
 *   https://developer.android.com/reference/android/content/ContentResolver
 */
internal class CalendarCreateToolHandler(
    private val context: Context,
    private val undoRecorder: UndoRecorder,
    private val support: CalendarSupport,
) : ToolHandler {

    override val name = "calendar_create"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val titleArg = arguments["title"] as? JsonPrimitive
            val startArg = arguments["start"] as? JsonPrimitive
            val endArg = arguments["end"] as? JsonPrimitive
            if (titleArg == null || startArg == null || endArg == null) {
                return@withContext ToolResult(false, "Укажите title, start, end.")
            }
            val title = titleArg.content
            val start = CalendarTimeParser.parse(startArg.content)
            val end = CalendarTimeParser.parse(endArg.content, endOfDay = true)
            val reminderArg = arguments["reminder"] as? JsonPrimitive
            val reminderMinutes = if (reminderArg != null) {
                reminderArg.content.toLongOrNull()
            } else {
                null
            }

            val calendarId = support.findWritableCalendarId()
            if (calendarId < 0) {
                return@withContext ToolResult(false, "Нет доступного календаря для записи.")
            }

            val values = ContentValues().apply {
                put(Events.DTSTART, start)
                put(Events.DTEND, end)
                put(Events.TITLE, title)
                put(Events.CALENDAR_ID, calendarId)
                put(Events.EVENT_TIMEZONE, "UTC")
            }
            val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
                ?: return@withContext ToolResult(false, "Не удалось создать событие.")
            val eventId = uri.lastPathSegment?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Не удалось определить ID события.")

            if (reminderMinutes != null) {
                val reminderValues = ContentValues().apply {
                    put(Reminders.MINUTES, reminderMinutes)
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                }
                context.contentResolver.insert(Reminders.CONTENT_URI, reminderValues)
            }

            undoRecorder.record(
                toolName = "calendar_create",
                description = "Создано событие #$eventId: $title",
                undoKind = UndoKinds.CALENDAR_DELETE,
                payload = mapOf(UndoPayload.ID to eventId.toString()),
            )
            ToolResult(true, "Событие создано: $title")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
