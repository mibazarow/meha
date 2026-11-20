package com.andharness.ru.data.tools.calendar

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoRecorder

/**
 * Builds one [ToolHandler] per calendar tool: `calendar_events`, `calendar_list`,
 * `calendar_instances`, `calendar_accounts_list`, `calendar_device_events`, `calendar_create`,
 * `calendar_update`, `calendar_delete`, `calendar_attendees_set`, `calendar_reminders_set`,
 * `calendar_recurring_create`, in their original registration order (the two device-account read
 * tools were added after the existing read group). This class is a thin coordinator; each tool
 * lives in its own `<Tool>Handler` class and the shared provider queries are in [CalendarSupport].
 *
 * References:
 * - Calendar Provider overview (Events / Calendars / Reminders tables, insert rules):
 *   https://developer.android.com/identity/providers/calendar-provider
 * - `CalendarContract.Events`:
 *   https://developer.android.com/reference/android/provider/CalendarContract.Events
 * - `CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL` (writable = OWNER/EDITOR/CONTRIBUTOR):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Calendars
 * - `CalendarContract.Reminders` (EVENT_ID, MINUTES, METHOD required on insert):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Reminders
 * - `ContentResolver.insert(uri, values)` / `query(...)`:
 *   https://developer.android.com/reference/android/content/ContentResolver
 */
class CalendarToolsHandler(
    private val context: Context,
    private val undoRecorder: UndoRecorder = UndoRecorder.NoOp,
) {

    fun handlers(): List<ToolHandler> {
        val support = CalendarSupport(context)
        return listOf(
            CalendarEventsToolHandler(context),
            CalendarListToolHandler(context),
            CalendarInstancesToolHandler(context),
            CalendarAccountsListToolHandler(context),
            CalendarDeviceEventsToolHandler(context),
            CalendarCreateToolHandler(context, undoRecorder, support),
            CalendarUpdateToolHandler(context),
            CalendarDeleteToolHandler(context),
            CalendarAttendeesSetToolHandler(context, support),
            CalendarRemindersSetToolHandler(context, support),
            CalendarRecurringCreateToolHandler(context, support),
        )
    }
}
