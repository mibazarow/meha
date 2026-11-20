package com.andharness.ru.data.tools.calendar

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract.Events
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_recurring_create`: creates an event carrying an RFC5545 RRULE and verifies the stored
 * rule matches.
 *
 * Reference: `CalendarContract.Events` (either DTEND or DURATION, never both) —
 * https://developer.android.com/reference/android/provider/CalendarContract.Events
 */
internal class CalendarRecurringCreateToolHandler(
    private val context: Context,
    private val support: CalendarSupport,
) : ToolHandler {

    override val name = "calendar_recurring_create"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val title = (arguments["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (title.isEmpty()) {
                return@withContext ToolResult(false, "Укажите title.")
            }
            val dtstartRaw = (arguments["dtstart"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (dtstartRaw.isEmpty()) {
                return@withContext ToolResult(false, "Укажите dtstart (ISO-дата или epoch millis).")
            }
            val rrule = (arguments["rrule"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (rrule.isEmpty()) {
                return@withContext ToolResult(false, "Укажите rrule, например FREQ=WEEKLY;COUNT=10.")
            }
            val durationRaw = (arguments["duration"] as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
            val dtendRaw = (arguments["dtend"] as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (durationRaw == null && dtendRaw == null) {
                return@withContext ToolResult(false, "Укажите duration (RFC5545, напр. PT1H) или dtend.")
            }
            val start = try {
                CalendarTimeParser.parse(dtstartRaw)
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Некорректный dtstart: «$dtstartRaw».")
            }
            val calendarId = (arguments["calendar_id"] as? JsonPrimitive)?.content?.trim()?.toLongOrNull()
                ?: support.findWritableCalendarId()
            if (calendarId < 0) {
                return@withContext ToolResult(false, "Нет доступного календаря для записи.")
            }

            // A recurring event carries RRULE (RFC 5545). Events need either DTEND or
            // DURATION (never both) plus a timezone.
            // https://developer.android.com/reference/android/provider/CalendarContract.Events
            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, title)
                put(Events.DTSTART, start)
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.RRULE, rrule)
                if (durationRaw != null) {
                    put(Events.DURATION, support.rfc2445Duration(durationRaw))
                } else {
                    put(Events.DTEND, CalendarTimeParser.parse(dtendRaw!!, endOfDay = true))
                }
            }
            val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
                ?: return@withContext ToolResult(false, "Не удалось создать повторяющееся событие.")
            val eventId = uri.lastPathSegment?.toLongOrNull()
                ?: return@withContext ToolResult(false, "Не удалось определить ID события.")

            val storedRrule = support.queryEventRrule(eventId)
            if (storedRrule == rrule) {
                ToolResult(true, "Повторяющееся событие создано: $title (id=$eventId, rrule=$rrule).")
            } else {
                ToolResult(false, "RRULE не подтвердился после записи (получено: $storedRrule).")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на изменение календаря (нужен WRITE_CALENDAR).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
