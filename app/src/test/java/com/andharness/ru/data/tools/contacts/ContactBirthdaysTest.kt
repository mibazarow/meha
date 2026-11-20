package com.andharness.ru.data.tools.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContactBirthdaysTest {

    // 2026-09-21 is a Monday; neither 2026 nor 2027 is a leap year.
    private val today: LocalDate = LocalDate.of(2026, 9, 21)

    // ---- parsing ----

    @Test
    fun parsesIsoDateWithYear() {
        assertEquals(ContactBirthdays.Birthday(5, 14, 1990), ContactBirthdays.parse("1990-05-14"))
    }

    @Test
    fun parsesYearlessPlatformFormat() {
        assertEquals(ContactBirthdays.Birthday(5, 14, null), ContactBirthdays.parse("--05-14"))
    }

    @Test
    fun parsesMonthDayWithoutPrefix() {
        assertEquals(ContactBirthdays.Birthday(5, 14, null), ContactBirthdays.parse("05-14"))
        assertEquals(ContactBirthdays.Birthday(5, 14, null), ContactBirthdays.parse("5/14"))
        assertEquals(ContactBirthdays.Birthday(5, 14, null), ContactBirthdays.parse("5.14"))
    }

    @Test
    fun stripsTrailingTimePart() {
        assertEquals(ContactBirthdays.Birthday(5, 14, 1990), ContactBirthdays.parse("1990-05-14T00:00:00"))
        assertEquals(ContactBirthdays.Birthday(5, 14, 1990), ContactBirthdays.parse("1990-05-14 12:30:00"))
    }

    @Test
    fun rejectsBlankAndUnparseable() {
        assertNull(ContactBirthdays.parse(null))
        assertNull(ContactBirthdays.parse("   "))
        assertNull(ContactBirthdays.parse("не дата"))
        assertNull(ContactBirthdays.parse("1990"))
        assertNull(ContactBirthdays.parse("1990-13-01"))
        assertNull(ContactBirthdays.parse("1990-02-30"))
        assertNull(ContactBirthdays.parse("--02-30"))
    }

    @Test
    fun preservesLeapDay() {
        assertEquals(ContactBirthdays.Birthday(2, 29, null), ContactBirthdays.parse("--02-29"))
        assertEquals(ContactBirthdays.Birthday(2, 29, 2000), ContactBirthdays.parse("2000-02-29"))
    }

    // ---- occurrence / leap-day handling ----

    @Test
    fun leapDayFallsOnLastDayOfFebruaryInNonLeapYear() {
        assertEquals(LocalDate.of(2025, 2, 28), ContactBirthdays.occurrenceIn(2025, 2, 29))
        assertEquals(LocalDate.of(2026, 2, 28), ContactBirthdays.occurrenceIn(2026, 2, 29))
    }

    @Test
    fun leapDayStaysOnFebruary29InLeapYear() {
        assertEquals(LocalDate.of(2028, 2, 29), ContactBirthdays.occurrenceIn(2028, 2, 29))
    }

    @Test
    fun ordinaryDateIsUnchanged() {
        assertEquals(LocalDate.of(2026, 5, 14), ContactBirthdays.occurrenceIn(2026, 5, 14))
    }

    // ---- days until ----

    @Test
    fun birthdayTodayIsZero() {
        assertEquals(0, ContactBirthdays.daysUntil(9, 21, today))
    }

    @Test
    fun birthdayTomorrowIsOne() {
        assertEquals(1, ContactBirthdays.daysUntil(9, 22, today))
    }

    @Test
    fun birthdayAlreadyPassedRollsOverToNextYear() {
        // 2026-09-20 already passed, so the next occurrence is 2027-09-20: 364 days.
        assertEquals(364, ContactBirthdays.daysUntil(9, 20, today))
    }

    @Test
    fun birthdayNextYearAroundTheNewYear() {
        // 2027-01-01 is 102 days after 2026-09-21.
        assertEquals(102, ContactBirthdays.daysUntil(1, 1, today))
    }

    @Test
    fun leapDayBirthdayIsTodayOnLastDayOfFebruaryWhenNotALeapYear() {
        assertEquals(0, ContactBirthdays.daysUntil(2, 29, LocalDate.of(2026, 2, 28)))
    }

    @Test
    fun leapDayBirthdayIsTomorrowOnLastDayOfFebruaryBeforeLeapDay() {
        assertEquals(1, ContactBirthdays.daysUntil(2, 29, LocalDate.of(2028, 2, 28)))
    }

    // ---- window + sorting ----

    private fun entry(id: String, name: String, date: String) =
        ContactBirthdays.Entry(contactId = id, name = name, rawDate = date)

    @Test
    fun upcomingFiltersByWindowAndSortsSoonestFirst() {
        val entries = listOf(
            entry("1", "Позже", "2026-10-21"),   // 30 days -> included (boundary)
            entry("2", "Сегодня", "2026-09-21"),  // 0 days
            entry("3", "Слишком поздно", "2026-10-22"), // 31 days -> excluded
            entry("4", "Завтра", "09-22"),        // 1 day, year-less
            entry("5", "Прошло", "2026-09-01"),   // rolled over to 2027 -> excluded
        )

        val result = ContactBirthdays.upcoming(entries, today, windowDays = 30)

        assertEquals(listOf("2", "4", "1"), result.map { it.contactId })
        assertEquals(listOf(0, 1, 30), result.map { it.daysUntil })
        assertFalse(result.first { it.contactId == "4" }.hasYear)
    }

    @Test
    fun upcomingWindowZeroKeepsOnlyToday() {
        val entries = listOf(
            entry("1", "Сегодня", "2026-09-21"),
            entry("2", "Завтра", "2026-09-22"),
        )

        val result = ContactBirthdays.upcoming(entries, today, windowDays = 0)

        assertEquals(listOf("1"), result.map { it.contactId })
    }

    @Test
    fun upcomingTiesAreBrokenByNameThenId() {
        val entries = listOf(
            entry("10", "Борис", "2026-09-25"),
            entry("11", "Анна", "2026-09-25"),
            entry("12", "Анна", "2026-09-25"),
        )

        val result = ContactBirthdays.upcoming(entries, today, windowDays = 30)

        assertEquals(listOf("11", "12", "10"), result.map { it.contactId })
    }

    @Test
    fun upcomingDropsBrokenRowsWithoutFailing() {
        val entries = listOf(
            entry("1", "Валидный", "1990-09-22"),
            entry("2", "Мусор", "не дата"),
            entry("3", "Пусто", ""),
        )

        val result = ContactBirthdays.upcoming(entries, today, windowDays = 30)

        assertEquals(listOf("1"), result.map { it.contactId })
        assertEquals("1990-09-22", result.single().originalDate)
        assertTrue(result.single().hasYear)
    }

    @Test
    fun upcomingWithEmptyInputIsEmpty() {
        assertTrue(ContactBirthdays.upcoming(emptyList(), today, 30).isEmpty())
    }

    @Test
    fun formatsMonthDayZeroPadded() {
        assertEquals("05-04", ContactBirthdays.formatMonthDay(5, 4))
        assertEquals("12-31", ContactBirthdays.formatMonthDay(12, 31))
    }
}
