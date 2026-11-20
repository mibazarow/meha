package com.andharness.ru.data.tools.calendar

/**
 * Pure, Android-free logic shared by the device-calendar READ tools
 * (`calendar_accounts_list`, `calendar_device_events`).
 *
 * The Android handlers own the `ContentResolver`/`CalendarContract` plumbing and adapt the real
 * provider `Cursor` to [DeviceCalendarCursor]; everything below is plain Kotlin so the projection
 * mapping, the time-window clamp, the cursor -> event mapping and the honest empty handling are
 * covered by JVM tests without an Android runtime.
 *
 * Provider sync is delegated to the system calendar apps: when the user has a Google/Microsoft
 * account, those apps sync it into `CalendarContract` on the device, and these tools read it back
 * through the same provider as the local calendar. The app does NOT speak OAuth/CalDAV itself.
 *
 * Column names mirror `CalendarContract` exactly (they are part of the platform's stable provider
 * contract):
 * - `CalendarContract.Calendars` (`_ID`, `calendar_displayName`, `account_name`, `account_type`,
 *   `visible`, `sync_events`, `calendar_access_level`):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Calendars
 * - `CalendarContract.CalendarColumns` (`calendar_id`, `visible`, `sync_events`,
 *   `calendar_access_level`) and `CAL_ACCESS_*` levels:
 *   https://developer.android.com/reference/android/provider/CalendarContract.CalendarColumns
 * - `CalendarContract.Instances` (`event_id`, `begin`, `end`, `calendar_id`):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Instances
 * - `CalendarContract.EventsColumns` (`title`):
 *   https://developer.android.com/reference/android/provider/CalendarContract.EventsColumns
 */

/** Minimal read-only cursor shape the mapping helpers need (adapted from `android.database.Cursor`). */
interface DeviceCalendarCursor {
    fun moveToNext(): Boolean
    fun getLong(column: String): Long
    fun getInt(column: String): Int
    fun getString(column: String): String?
}

/** One row of `CalendarContract.Calendars`: which account it belongs to and how it is configured. */
data class CalendarAccount(
    val id: Long,
    val displayName: String?,
    val accountName: String?,
    val accountType: String?,
    val visible: Boolean,
    val syncEvents: Boolean,
    val accessLevel: Int,
)

/** One occurrence from `CalendarContract.Instances`, already joined with its calendar/account. */
data class DeviceCalendarEvent(
    val eventId: Long,
    val title: String?,
    val begin: Long,
    val end: Long,
    val calendarId: Long,
    val calendarName: String?,
    val accountName: String?,
    val accountType: String?,
)

/** A validated, bounded query window ([from] is preserved; [to] may have been clamped). */
data class DeviceCalendarWindow(
    val from: Long,
    val to: Long,
    val clamped: Boolean,
)

object DeviceCalendarLogic {

    // CalendarContract column names — see the file header for the doc links.
    const val COL_ID = "_id"
    const val COL_DISPLAY_NAME = "calendar_displayName"
    const val COL_ACCOUNT_NAME = "account_name"
    const val COL_ACCOUNT_TYPE = "account_type"
    const val COL_VISIBLE = "visible"
    const val COL_SYNC_EVENTS = "sync_events"
    const val COL_ACCESS_LEVEL = "calendar_access_level"
    const val COL_EVENT_ID = "event_id"
    const val COL_TITLE = "title"
    const val COL_BEGIN = "begin"
    const val COL_END = "end"
    const val COL_CALENDAR_ID = "calendar_id"

    /**
     * Upper bound for one device-calendar query window (400 days). The provider expands recurring
     * events per occurrence, so an unbounded window can be huge; the window is clamped to this span.
     */
    const val MAX_WINDOW_MS: Long = 400L * 24 * 60 * 60 * 1000

    /**
     * Validates and bounds a `[from, to]` window. Returns `null` when [to] is earlier than [from]
     * (honest error), otherwise a window whose [DeviceCalendarWindow.to] is at most
     * [maxWindowMs] after [from]; [DeviceCalendarWindow.clamped] records whether it was shortened.
     */
    fun clampWindow(from: Long, to: Long, maxWindowMs: Long = MAX_WINDOW_MS): DeviceCalendarWindow? {
        if (to < from) return null
        val capped = if (maxWindowMs > 0 && to - from > maxWindowMs) from + maxWindowMs else to
        return DeviceCalendarWindow(from = from, to = capped, clamped = capped != to)
    }

    /** Maps the `CalendarContract.Calendars` projection rows to [CalendarAccount]s. Honest empty. */
    fun readCalendars(cursor: DeviceCalendarCursor): List<CalendarAccount> {
        val result = ArrayList<CalendarAccount>()
        while (cursor.moveToNext()) {
            result.add(
                CalendarAccount(
                    id = cursor.getLong(COL_ID),
                    displayName = cursor.getString(COL_DISPLAY_NAME),
                    accountName = cursor.getString(COL_ACCOUNT_NAME),
                    accountType = cursor.getString(COL_ACCOUNT_TYPE),
                    visible = cursor.getInt(COL_VISIBLE) == 1,
                    syncEvents = cursor.getInt(COL_SYNC_EVENTS) == 1,
                    accessLevel = cursor.getInt(COL_ACCESS_LEVEL),
                ),
            )
        }
        return result
    }

    /**
     * Maps the `CalendarContract.Instances` projection rows to [DeviceCalendarEvent]s, joining each
     * event's `calendar_id` against [calendars] built by [readCalendars] so the result carries the
     * calendar display name and account. When a calendar is unknown (e.g. deleted mid-query) the
     * name/account fields stay `null` — never fabricated.
     */
    fun readEvents(
        cursor: DeviceCalendarCursor,
        calendars: Map<Long, CalendarAccount>,
    ): List<DeviceCalendarEvent> {
        val result = ArrayList<DeviceCalendarEvent>()
        while (cursor.moveToNext()) {
            val calendarId = cursor.getLong(COL_CALENDAR_ID)
            val calendar = calendars[calendarId]
            result.add(
                DeviceCalendarEvent(
                    eventId = cursor.getLong(COL_EVENT_ID),
                    title = cursor.getString(COL_TITLE),
                    begin = cursor.getLong(COL_BEGIN),
                    end = cursor.getLong(COL_END),
                    calendarId = calendarId,
                    calendarName = calendar?.displayName,
                    accountName = calendar?.accountName,
                    accountType = calendar?.accountType,
                ),
            )
        }
        return result
    }

    /** Renders the accounts/calendars report; an honest line when the device has none. */
    fun formatAccounts(calendars: List<CalendarAccount>): String {
        if (calendars.isEmpty()) {
            return "Аккаунты и календари не найдены (нет доступных провайдеров календаря)."
        }
        val accounts = calendars.map { it.accountName to it.accountType }.distinct()
        val header = "Аккаунтов: ${accounts.size}, календарей: ${calendars.size}. " +
            accounts.joinToString(", ") { "${it.first} (${it.second})" }
        val rows = calendars.map { cal ->
            "id=${cal.id} | ${cal.displayName ?: "(без названия)"} | " +
                "аккаунт=${cal.accountName} (${cal.accountType}) | " +
                "видимый=${yesNo(cal.visible)} | синхронизация=${yesNo(cal.syncEvents)} | " +
                "доступ=${cal.accessLevel} (${accessLabel(cal.accessLevel)})"
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    /** Renders the device events report; an honest line when the window is empty. */
    fun formatEvents(events: List<DeviceCalendarEvent>): String {
        if (events.isEmpty()) {
            return "События за период не найдены."
        }
        return events.joinToString("\n") { event ->
            val calendar = event.calendarName ?: "(календарь #${event.calendarId})"
            val account = when {
                event.accountName == null -> "(аккаунт неизвестен)"
                event.accountType == null -> event.accountName
                else -> "${event.accountName} (${event.accountType})"
            }
            "${event.title ?: "(без названия)"} | " +
                "начало=${event.begin} | конец=${event.end} | " +
                "календарь=$calendar | аккаунт=$account (eventId=${event.eventId})"
        }
    }

    private fun yesNo(value: Boolean) = if (value) "да" else "нет"

    /** Russian labels for `CalendarContract.CalendarColumns.CAL_ACCESS_*` levels. */
    private fun accessLabel(level: Int): String = when {
        level >= 800 -> "root"
        level >= 700 -> "владелец"
        level >= 600 -> "редактор"
        level >= 500 -> "участник"
        level >= 400 -> "переопределение"
        level >= 300 -> "ответ"
        level >= 200 -> "чтение"
        level >= 100 -> "занятость"
        else -> "нет"
    }
}
