package com.andharness.ru.data.tools.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * JVM tests for [CalendarTimeParser]. Pure `java.time` only — no Android runtime.
 *
 * References:
 * - `java.time.LocalDate` / `LocalTime` / `ZoneId`:
 *   https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/LocalDate.html
 * - `java.time.Instant.parse`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html
 */
class CalendarTimeParserTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun epochMillisPassesThroughUnchanged() {
        assertEquals(1_788_246_000_000L, CalendarTimeParser.parse("1788246000000", zone = zone))
        assertEquals(1_788_246_000_000L, CalendarTimeParser.parse("  1788246000000  ", zone = zone))
    }

    @Test
    fun dateOnlyFromIsStartOfDayInGivenZone() {
        val expected = LocalDate.parse("2026-09-01").atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01", zone = zone))
    }

    @Test
    fun dateOnlyToIsEndOfDayInGivenZone() {
        val expected = LocalDate.parse("2026-09-01")
            .atTime(LocalTime.MAX)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01", endOfDay = true, zone = zone))
    }

    @Test
    fun dateOnlyUsesSystemDefaultZoneByDefault() {
        val expected = LocalDate.parse("2026-09-01")
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01"))
    }

    @Test
    fun endOfDayIsAfterStartOfDayAndSameDay() {
        val start = CalendarTimeParser.parse("2026-09-01", zone = zone)
        val end = CalendarTimeParser.parse("2026-09-01", endOfDay = true, zone = zone)
        assertTrue(end > start)
        assertEquals(LocalDate.parse("2026-09-01"), Instant.ofEpochMilli(end).atZone(zone).toLocalDate())
    }

    @Test
    fun fullIsoDateTimeWithOffsetIsParsed() {
        val expected = Instant.parse("2026-09-01T07:00:00Z").toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01T10:00:00+03:00", zone = zone))
    }

    @Test
    fun fullIsoDateTimeWithZuluIsParsed() {
        val expected = Instant.parse("2026-09-01T10:00:00Z").toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01T10:00:00Z", zone = zone))
    }

    @Test
    fun offsetDateTimeWithoutSecondsIsParsed() {
        val expected = Instant.parse("2026-09-01T07:00:00Z").toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01T10:00+03:00", zone = zone))
    }

    @Test
    fun naiveLocalDateTimeUsesGivenZone() {
        val expected = LocalDateTime.parse("2026-09-01T10:00")
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, CalendarTimeParser.parse("2026-09-01T10:00", zone = zone))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidStringThrows() {
        CalendarTimeParser.parse("not-a-date", zone = zone)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankStringThrows() {
        CalendarTimeParser.parse("   ", zone = zone)
    }
}
