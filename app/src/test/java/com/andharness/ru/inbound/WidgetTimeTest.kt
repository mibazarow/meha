package com.andharness.ru.inbound

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the widget's pure time label. Clock and zone are injected, so every expectation is
 * exact and independent of the machine's locale/timezone.
 */
class WidgetTimeTest {

    private val zone = ZoneId.of("UTC")

    /** 2026-01-15T10:00:00Z. */
    private val now = ms("2026-01-15T10:00:00Z")

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun format(updatedIso: String): String = formatWidgetTime(ms(updatedIso), now, zone)

    @Test
    fun `same day shows the clock time`() {
        assertEquals("09:05", format("2026-01-15T09:05:00Z"))
        assertEquals("00:01", format("2026-01-15T00:01:00Z"))
    }

    @Test
    fun `yesterday is a calendar day, not a 24 hour window`() {
        // Only two minutes earlier, but a different date.
        assertEquals("вчера", format("2026-01-14T23:58:00Z"))
    }

    @Test
    fun `two to six days ago shows the day count`() {
        assertEquals("2 дн", format("2026-01-13T10:00:00Z"))
        assertEquals("3 дн", format("2026-01-12T10:00:00Z"))
        assertEquals("6 дн", format("2026-01-09T10:00:00Z"))
    }

    @Test
    fun `a week or older shows the date`() {
        assertEquals("08.01.26", format("2026-01-08T10:00:00Z"))
        assertEquals("17.09.25", format("2025-09-17T10:00:00Z"))
    }

    @Test
    fun `a clock skewed future value falls back to the time`() {
        // Device clock moved back after the row was written: days <= 0 must not produce a negative
        // "дн" label.
        assertEquals("12:34", format("2026-01-15T12:34:00Z"))
    }

    @Test
    fun `the zone decides which calendar day the row belongs to`() {
        // The same pair of instants straddles midnight in UTC (yesterday) but not in UTC+1 (both on
        // the same local date), so the label follows the injected zone.
        val updated = ms("2026-01-14T23:30:00Z")
        val reference = ms("2026-01-15T00:30:00Z")
        assertEquals("вчера", formatWidgetTime(updated, reference, zone))
        assertEquals("00:30", formatWidgetTime(updated, reference, ZoneId.of("UTC+1")))
    }
}
