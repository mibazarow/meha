package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the pure time parser of the `reminder_set` tool: relative delays
 * (`5` = 5 minutes, `30s`, `5m`, `2h`, `1d`), absolute epochs (seconds vs millis) and local `HH:MM`.
 * The clock and zone are injected, so every expectation is exact.
 */
class ReminderTimeTest {

    private val zone = ZoneId.of("UTC")

    /** 2026-01-15T10:00:00Z. */
    private val now = Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()

    private val minute = 60_000L
    private val second = 1_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun ok(result: ReminderTime.Result): Long {
        assertTrue("expected Ok but was $result", result is ReminderTime.Result.Ok)
        return (result as ReminderTime.Result.Ok).atMs
    }

    private fun error(result: ReminderTime.Result) {
        assertTrue("expected Error but was $result", result is ReminderTime.Result.Error)
    }

    // --- fromDelay ---------------------------------------------------------------------------

    @Test
    fun bareNumberMeansMinutes() {
        assertEquals(now + 5 * minute, ok(ReminderTime.fromDelay("5", now)))
    }

    @Test
    fun explicitMinutesMatchBareNumber() {
        assertEquals(now + 5 * minute, ok(ReminderTime.fromDelay("5m", now)))
    }

    @Test
    fun secondsHoursAndDaysAreConverted() {
        assertEquals(now + 30 * second, ok(ReminderTime.fromDelay("30s", now)))
        assertEquals(now + 2 * hour, ok(ReminderTime.fromDelay("2h", now)))
        assertEquals(now + day, ok(ReminderTime.fromDelay("1d", now)))
    }

    @Test
    fun suffixIsCaseInsensitiveAndInputIsTrimmed() {
        assertEquals(now + 5 * minute, ok(ReminderTime.fromDelay(" 5M ", now)))
        assertEquals(now + 2 * hour, ok(ReminderTime.fromDelay("2H", now)))
        assertEquals(now + 30 * second, ok(ReminderTime.fromDelay("30S", now)))
        assertEquals(now + day, ok(ReminderTime.fromDelay("1D", now)))
    }

    @Test
    fun malformedDelaysAreErrors() {
        error(ReminderTime.fromDelay(null, now))
        error(ReminderTime.fromDelay("", now))
        error(ReminderTime.fromDelay("   ", now))
        error(ReminderTime.fromDelay("скоро", now))
        error(ReminderTime.fromDelay("5x", now))
        error(ReminderTime.fromDelay("5.5m", now))
        error(ReminderTime.fromDelay("1h30m", now))
        error(ReminderTime.fromDelay("-5", now))
        error(ReminderTime.fromDelay("-5m", now))
    }

    @Test
    fun zeroDelaysAreErrors() {
        error(ReminderTime.fromDelay("0", now))
        error(ReminderTime.fromDelay("0m", now))
        error(ReminderTime.fromDelay("00s", now))
    }

    @Test
    fun maximumDelayIsAcceptedAndBeyondIsRejected() {
        assertEquals(now + 365 * day, ok(ReminderTime.fromDelay("365d", now)))
        error(ReminderTime.fromDelay("366d", now))
        error(ReminderTime.fromDelay("8761h", now))
    }

    // --- fromAt: epoch -----------------------------------------------------------------------

    @Test
    fun epochMillisPassesThrough() {
        val target = ms("2026-02-01T08:00:00Z")
        assertEquals(target, ok(ReminderTime.fromAt(target.toString(), now, zone)))
    }

    @Test
    fun epochSecondsAreConvertedToMillis() {
        val targetSeconds = ms("2026-02-01T08:00:00Z") / 1000L
        assertEquals(targetSeconds * 1000L, ok(ReminderTime.fromAt(targetSeconds.toString(), now, zone)))
    }

    // --- fromAt: HH:MM -----------------------------------------------------------------------

    @Test
    fun clockTimeLaterTodayResolvesToday() {
        assertEquals(ms("2026-01-15T11:30:00Z"), ok(ReminderTime.fromAt("11:30", now, zone)))
    }

    @Test
    fun clockTimeAlreadyPassedResolvesTomorrow() {
        assertEquals(ms("2026-01-16T09:30:00Z"), ok(ReminderTime.fromAt("09:30", now, zone)))
    }

    @Test
    fun clockTimeExactlyNowResolvesTomorrow() {
        assertEquals(ms("2026-01-16T10:00:00Z"), ok(ReminderTime.fromAt("10:00", now, zone)))
    }

    @Test
    fun clockTimeUsesTheGivenZone() {
        val moscow = ZoneId.of("Europe/Moscow")
        assertEquals(ms("2026-01-15T10:30:00Z"), ok(ReminderTime.fromAt("13:30", now, moscow)))
    }

    // --- fromAt: errors ----------------------------------------------------------------------

    @Test
    fun malformedAtValuesAreErrors() {
        error(ReminderTime.fromAt(null, now, zone))
        error(ReminderTime.fromAt("", now, zone))
        error(ReminderTime.fromAt("завтра", now, zone))
        error(ReminderTime.fromAt("24:00", now, zone))
        error(ReminderTime.fromAt("10:60", now, zone))
        error(ReminderTime.fromAt("10:00:00", now, zone))
        error(ReminderTime.fromAt("0", now, zone))
        error(ReminderTime.fromAt("-5", now, zone))
    }
}
