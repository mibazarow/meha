package com.andharness.ru.data.tools.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [DeviceCalendarLogic] — the pure, Android-free half of the device-calendar read
 * tools. No Android runtime: the Calendar Provider `Cursor` is replaced by a tiny [FakeCursor] that
 * walks a list of column -> value maps, exactly as `android.database.Cursor` would.
 *
 * Covered: account/calendar projection mapping (visible/sync flags, access level), time-window
 * clamping, cursor -> event mapping with the calendar/account join and the honest unknown-calendar
 * fallback, and the honest empty handling of both formatters.
 */
class DeviceCalendarLogicTest {

    // --- account / calendar projection mapping ---

    @Test
    fun readCalendarsMapsEveryProjectedField() {
        val cursor = FakeCursor(
            listOf(
                mapOf(
                    DeviceCalendarLogic.COL_ID to 7L,
                    DeviceCalendarLogic.COL_DISPLAY_NAME to "Работа",
                    DeviceCalendarLogic.COL_ACCOUNT_NAME to "me@corp.com",
                    DeviceCalendarLogic.COL_ACCOUNT_TYPE to "com.google",
                    DeviceCalendarLogic.COL_VISIBLE to 1,
                    DeviceCalendarLogic.COL_SYNC_EVENTS to 1,
                    DeviceCalendarLogic.COL_ACCESS_LEVEL to 700,
                ),
                mapOf(
                    DeviceCalendarLogic.COL_ID to 8L,
                    DeviceCalendarLogic.COL_DISPLAY_NAME to "Праздники",
                    DeviceCalendarLogic.COL_ACCOUNT_NAME to "me@corp.com",
                    DeviceCalendarLogic.COL_ACCOUNT_TYPE to "com.google",
                    DeviceCalendarLogic.COL_VISIBLE to 0,
                    DeviceCalendarLogic.COL_SYNC_EVENTS to 0,
                    DeviceCalendarLogic.COL_ACCESS_LEVEL to 200,
                ),
            ),
        )

        val calendars = DeviceCalendarLogic.readCalendars(cursor)

        assertEquals(2, calendars.size)
        assertEquals(
            CalendarAccount(
                id = 7L,
                displayName = "Работа",
                accountName = "me@corp.com",
                accountType = "com.google",
                visible = true,
                syncEvents = true,
                accessLevel = 700,
            ),
            calendars[0],
        )
        assertFalse(calendars[1].visible)
        assertFalse(calendars[1].syncEvents)
        assertEquals(200, calendars[1].accessLevel)
    }

    @Test
    fun readCalendarsOnEmptyCursorIsEmpty() {
        assertTrue(DeviceCalendarLogic.readCalendars(FakeCursor(emptyList())).isEmpty())
    }

    // --- formatting of accounts / calendars ---

    @Test
    fun formatAccountsIsHonestWhenEmpty() {
        val text = DeviceCalendarLogic.formatAccounts(emptyList())
        assertTrue(text.contains("не найдены"))
    }

    @Test
    fun formatAccountsListsAccountCalendarAndFlags() {
        val text = DeviceCalendarLogic.formatAccounts(
            listOf(
                CalendarAccount(1L, "Мой календарь", "user@gmail.com", "com.google", true, true, 700),
                CalendarAccount(2L, "Локальный", "local", "LOCAL", false, false, 200),
            ),
        )
        assertTrue(text.contains("Аккаунтов: 2"))
        assertTrue(text.contains("user@gmail.com (com.google)"))
        assertTrue(text.contains("local (LOCAL)"))
        assertTrue(text.contains("Мой календарь"))
        assertTrue(text.contains("видимый=да"))
        assertTrue(text.contains("синхронизация=нет"))
        assertTrue(text.contains("доступ=700 (владелец)"))
    }

    // --- time-window clamping ---

    @Test
    fun clampWindowKeepsAShortWindowUnchanged() {
        val window = DeviceCalendarLogic.clampWindow(1_000L, 2_000L)
        assertEquals(1_000L, window?.from)
        assertEquals(2_000L, window?.to)
        assertFalse(window!!.clamped)
    }

    @Test
    fun clampWindowBoundsAnOversizedWindow() {
        val from = 1_000L
        val window = DeviceCalendarLogic.clampWindow(from, from + DeviceCalendarLogic.MAX_WINDOW_MS * 3)
        assertEquals(from, window?.from)
        assertEquals(from + DeviceCalendarLogic.MAX_WINDOW_MS, window?.to)
        assertTrue(window!!.clamped)
    }

    @Test
    fun clampWindowAcceptsACustomBound() {
        val window = DeviceCalendarLogic.clampWindow(0L, 500L, maxWindowMs = 100L)
        assertEquals(100L, window?.to)
        assertTrue(window!!.clamped)
    }

    @Test
    fun clampWindowRejectsReversedRange() {
        assertNull(DeviceCalendarLogic.clampWindow(2_000L, 1_000L))
    }

    @Test
    fun clampWindowAllowsAZeroLengthWindow() {
        val window = DeviceCalendarLogic.clampWindow(1_000L, 1_000L)
        assertEquals(1_000L, window?.to)
        assertFalse(window!!.clamped)
    }

    // --- cursor -> event mapping (with the calendar/account join) ---

    private val calendarsById = mapOf(
        7L to CalendarAccount(7L, "Работа", "me@corp.com", "com.microsoft", true, true, 700),
    )

    @Test
    fun readEventsJoinsCalendarAndAccount() {
        val cursor = FakeCursor(
            listOf(
                mapOf(
                    DeviceCalendarLogic.COL_EVENT_ID to 101L,
                    DeviceCalendarLogic.COL_TITLE to "Созвон",
                    DeviceCalendarLogic.COL_BEGIN to 1_700_000_000_000L,
                    DeviceCalendarLogic.COL_END to 1_700_000_600_000L,
                    DeviceCalendarLogic.COL_CALENDAR_ID to 7L,
                ),
            ),
        )

        val events = DeviceCalendarLogic.readEvents(cursor, calendarsById)

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(101L, event.eventId)
        assertEquals("Созвон", event.title)
        assertEquals(1_700_000_000_000L, event.begin)
        assertEquals(1_700_000_600_000L, event.end)
        assertEquals("Работа", event.calendarName)
        assertEquals("me@corp.com", event.accountName)
        assertEquals("com.microsoft", event.accountType)
    }

    @Test
    fun readEventsLeavesUnknownCalendarFieldsNull() {
        val cursor = FakeCursor(
            listOf(
                mapOf(
                    DeviceCalendarLogic.COL_EVENT_ID to 202L,
                    DeviceCalendarLogic.COL_TITLE to "Без календаря",
                    DeviceCalendarLogic.COL_BEGIN to 10L,
                    DeviceCalendarLogic.COL_END to 20L,
                    DeviceCalendarLogic.COL_CALENDAR_ID to 999L,
                ),
            ),
        )

        val event = DeviceCalendarLogic.readEvents(cursor, calendarsById).single()
        assertNull(event.calendarName)
        assertNull(event.accountName)
        assertNull(event.accountType)
    }

    @Test
    fun readEventsOnEmptyCursorIsEmpty() {
        assertTrue(DeviceCalendarLogic.readEvents(FakeCursor(emptyList()), calendarsById).isEmpty())
    }

    // --- formatting of events ---

    @Test
    fun formatEventsIsHonestWhenEmpty() {
        val text = DeviceCalendarLogic.formatEvents(emptyList())
        assertTrue(text.contains("не найдены"))
    }

    @Test
    fun formatEventsCarriesCalendarAndAccountPerEvent() {
        val event = DeviceCalendarEvent(
            eventId = 101L,
            title = "Созвон",
            begin = 1L,
            end = 2L,
            calendarId = 7L,
            calendarName = "Работа",
            accountName = "me@corp.com",
            accountType = "com.microsoft",
        )

        val text = DeviceCalendarLogic.formatEvents(listOf(event))

        assertTrue(text.contains("Созвон"))
        assertTrue(text.contains("календарь=Работа"))
        assertTrue(text.contains("аккаунт=me@corp.com (com.microsoft)"))
        assertTrue(text.contains("eventId=101"))
    }

    @Test
    fun formatEventsFallsBackHonestlyForUnknownCalendar() {
        val event = DeviceCalendarEvent(
            eventId = 5L,
            title = null,
            begin = 1L,
            end = 2L,
            calendarId = 999L,
            calendarName = null,
            accountName = null,
            accountType = null,
        )

        val text = DeviceCalendarLogic.formatEvents(listOf(event))

        assertTrue(text.contains("(без названия)"))
        assertTrue(text.contains("(календарь #999)"))
        assertTrue(text.contains("(аккаунт неизвестен)"))
    }

    /** List-backed [DeviceCalendarCursor] stand-in for `android.database.Cursor`. */
    private class FakeCursor(private val rows: List<Map<String, Any?>>) : DeviceCalendarCursor {

        private var index = -1

        override fun moveToNext(): Boolean {
            index += 1
            return index < rows.size
        }

        override fun getLong(column: String): Long = (rows[index][column] as? Number)?.toLong() ?: 0L

        override fun getInt(column: String): Int = (rows[index][column] as? Number)?.toInt() ?: 0

        override fun getString(column: String): String? = rows[index][column] as? String
    }
}
