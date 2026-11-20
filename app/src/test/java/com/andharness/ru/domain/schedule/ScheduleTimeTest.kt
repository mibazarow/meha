package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bug #1: an "HH:MM" schedule must resolve to the NEXT occurrence — today when that wall-clock time
 * is still in the future, otherwise tomorrow — and the wall clock must be interpreted in the device's
 * timezone. Deterministic: the "now" instant and zone are injected.
 */
class ScheduleTimeTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    /** 2026-09-17T17:45:00Z = 20:45 in Moscow, a Thursday. */
    private val now = Instant.parse("2026-09-17T17:45:00Z").toEpochMilli()

    private fun task(hour: Int, minute: Int, days: Int = ScheduledTask.EVERY_DAY): ScheduledTask =
        ScheduledTask(
            id = 1,
            prompt = "p",
            hour = hour,
            minute = minute,
            daysMask = days,
            enabled = true,
            lastRunAt = 0,
            chatId = null
        )

    private fun nextIso(hour: Int, minute: Int, days: Int = ScheduledTask.EVERY_DAY, zone: ZoneId = moscow): String =
        Instant.ofEpochMilli(now + ScheduleTime.nextDelayMillis(task(hour, minute, days), now, zone)).toString()

    private fun oneShot(atMs: Long, days: Int = ScheduledTask.EVERY_DAY): ScheduledTask =
        ScheduledTask(
            id = 2,
            prompt = "p",
            hour = 0,
            minute = 0,
            daysMask = days,
            enabled = true,
            lastRunAt = 0,
            chatId = null,
            atMs = atMs
        )

    @Test
    fun wallClockStillAheadTodayRunsToday() {
        // 21:40 MSK (= 18:40Z) is 55 minutes after "now".
        assertEquals("2026-09-17T18:40:00Z", nextIso(21, 40))
    }

    @Test
    fun wallClockAlreadyPassedRunsTomorrow() {
        // 07:00 MSK already passed today → tomorrow 2026-09-18 07:00 MSK = 04:00Z.
        assertEquals("2026-09-18T04:00:00Z", nextIso(7, 0))
    }

    @Test
    fun wallClockExactlyNowRunsTomorrow() {
        // The current minute is not re-triggered: the next occurrence is tomorrow.
        assertEquals("2026-09-18T17:45:00Z", nextIso(20, 45))
    }

    @Test
    fun weekdayMaskSkipsDaysThatDoNotMatch() {
        // 2026-09-17 is Thursday; a Monday-only task at a passed time must wait until Monday.
        assertEquals("2026-09-21T05:00:00Z", nextIso(8, 0, days = 1 shl 0))
    }

    @Test
    fun sameWallClockIsInterpretedInTheGivenTimezone() {
        // 21:40 is still ahead when read in UTC (17:45Z) but only ~55 min ahead in Moscow.
        assertEquals("2026-09-17T21:40:00Z", nextIso(21, 40, zone = ZoneId.of("UTC")))
    }

    @Test
    fun oneShotInTheFutureDelaysByExactlyTheDifference() {
        // 90 s ahead: a one-shot pays no weekday/timezone rounding.
        assertEquals(90_000L, ScheduleTime.nextDelayMillis(oneShot(now + 90_000L), now, moscow))
    }

    @Test
    fun oneShotExactlyNowIsDueImmediately() {
        assertEquals(0L, ScheduleTime.nextDelayMillis(oneShot(now), now, moscow))
    }

    @Test
    fun oneShotInThePastFiresImmediately() {
        // An overdue reminder must not be postponed: the delay is clamped at 0.
        assertEquals(0L, ScheduleTime.nextDelayMillis(oneShot(now - 5_000L), now, moscow))
    }

    @Test
    fun oneShotIgnoresTheWeekdayMask() {
        // daysMask = 0 matches nothing for a recurring task (24 h fallback); a one-shot must not
        // care about the mask at all.
        assertEquals(3_600_000L, ScheduleTime.nextDelayMillis(oneShot(now + 3_600_000L, days = 0), now, moscow))
    }

    @Test
    fun oneShotIsFlaggedOnTheModel() {
        assertEquals(true, oneShot(now + 1L).isOneShot)
        assertEquals(false, task(8, 0).isOneShot)
    }

    @Test
    fun delayIsAlwaysPositiveAndBoundedByAWeek() {
        for (hour in 0..23) {
            val delay = ScheduleTime.nextDelayMillis(task(hour, 30, ScheduledTask.WEEKDAYS), now, moscow)
            assertEquals(true, delay > 0L)
            assertEquals(true, delay <= 7L * 24 * 60 * 60 * 1000)
        }
    }
}
