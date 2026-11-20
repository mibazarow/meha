package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `calendar_device_events`: read-only list of events across ALL device calendars (Google/Microsoft/
 * local) in the bounded `[from, to]` window, using `CalendarContract.Instances` so recurring events
 * are expanded into their occurrences. Every returned event carries its calendar display name and
 * account, unlike the older local `calendar_instances`/`calendar_events` tools which report only
 * the calendar id.
 *
 * The window is clamped by [DeviceCalendarLogic.clampWindow] because the provider expands recurring
 * events per occurrence (an unbounded window can be enormous).
 *
 * Provider sync is delegated to the system calendar apps (no OAuth in the app); with no accounts or
 * calendars present this returns an honest empty result and never fabricates events.
 *
 * References:
 * - `CalendarContract.Instances.query(cr, projection, begin, end)`:
 *   https://developer.android.com/reference/android/provider/CalendarContract.Instances
 * - `CalendarContract.Instances` (`EVENT_ID`, `TITLE`, `BEGIN`, `END`, `CALENDAR_ID`):
 *   https://developer.android.com/reference/android/provider/CalendarContract
 */
internal class CalendarDeviceEventsToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_device_events"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val fromArg = arguments["from"] as? JsonPrimitive
            val toArg = arguments["to"] as? JsonPrimitive
            if (fromArg == null || toArg == null) {
                return@withContext ToolResult(false, "Укажите from и to (ISO-дата или epoch millis).")
            }
            val parsedFrom = try {
                CalendarTimeParser.parse(fromArg.content)
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Некорректный from: «${fromArg.content}».")
            }
            val parsedTo = try {
                CalendarTimeParser.parse(toArg.content, endOfDay = true)
            } catch (e: Exception) {
                return@withContext ToolResult(false, "Некорректный to: «${toArg.content}».")
            }
            val window = DeviceCalendarLogic.clampWindow(parsedFrom, parsedTo)
                ?: return@withContext ToolResult(false, "to раньше from.")

            // First read the calendars so each instance can be labelled with its calendar and account
            // (the Instances projection carries only calendar_id). Bounded window prevents the
            // provider from expanding an unbounded recurring set.
            val calendarProjection = arrayOf(
                DeviceCalendarLogic.COL_ID,
                DeviceCalendarLogic.COL_DISPLAY_NAME,
                DeviceCalendarLogic.COL_ACCOUNT_NAME,
                DeviceCalendarLogic.COL_ACCOUNT_TYPE,
                DeviceCalendarLogic.COL_VISIBLE,
                DeviceCalendarLogic.COL_SYNC_EVENTS,
                DeviceCalendarLogic.COL_ACCESS_LEVEL,
            )
            val calendars = context.contentResolver.query(
                Calendars.CONTENT_URI,
                calendarProjection,
                null,
                null,
                null,
            )?.use { cursor ->
                DeviceCalendarLogic.readCalendars(AndroidDeviceCalendarCursor(cursor))
            } ?: emptyList()
            val calendarsById = calendars.associateBy { it.id }

            val projection = arrayOf(
                DeviceCalendarLogic.COL_EVENT_ID,
                DeviceCalendarLogic.COL_TITLE,
                DeviceCalendarLogic.COL_BEGIN,
                DeviceCalendarLogic.COL_END,
                DeviceCalendarLogic.COL_CALENDAR_ID,
            )
            val events = Instances.query(
                context.contentResolver,
                projection,
                window.from,
                window.to,
            )?.use { cursor ->
                DeviceCalendarLogic.readEvents(AndroidDeviceCalendarCursor(cursor), calendarsById)
            } ?: emptyList()

            ToolResult(true, DeviceCalendarLogic.formatEvents(events))
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение календаря (нужен READ_CALENDAR).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
