package com.andharness.ru.data.tools.calendar

import android.content.Context
import android.provider.CalendarContract.Calendars
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `calendar_accounts_list`: read-only report of the device's calendar ACCOUNTS and CALENDARS from
 * `CalendarContract.Calendars` — account name/type, calendar display name, visibility, whether
 * events sync, and the access level. This is how the agent tells the user which providers are
 * available (e.g. Google/Microsoft synced by the system apps, or a local calendar).
 *
 * The app performs no OAuth/CalDAV itself: Google/Microsoft accounts are synced into
 * `CalendarContract` by the system calendar apps, and this tool reads that provider.
 *
 * References:
 * - `CalendarContract.Calendars` (`ACCOUNT_NAME`, `ACCOUNT_TYPE`, `VISIBLE`, `SYNC_EVENTS`,
 *   `CALENDAR_ACCESS_LEVEL`):
 *   https://developer.android.com/reference/android/provider/CalendarContract.Calendars
 * - `CalendarContract.SyncColumns` (`ACCOUNT_NAME`, `ACCOUNT_TYPE`):
 *   https://developer.android.com/reference/android/provider/CalendarContract.SyncColumns
 */
internal class CalendarAccountsListToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "calendar_accounts_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            // Column names come from DeviceCalendarLogic so the projection and the cursor reads can
            // never drift apart; they mirror CalendarContract's stable column names.
            val projection = arrayOf(
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
                projection,
                null,
                null,
                DeviceCalendarLogic.COL_DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                DeviceCalendarLogic.readCalendars(AndroidDeviceCalendarCursor(cursor))
            } ?: emptyList()

            ToolResult(true, DeviceCalendarLogic.formatAccounts(calendars))
        } catch (e: SecurityException) {
            ToolResult(false, "Нет разрешения на чтение календаря (нужен READ_CALENDAR).")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
