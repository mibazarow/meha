package com.andharness.ru.bg

import com.andharness.ru.domain.schedule.ScheduleCatchUpPlan
import com.andharness.ru.domain.schedule.ScheduleRunPlan
import com.andharness.ru.domain.schedule.ScheduledTask
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catch-up rules for scheduled tasks/reminders: a run missed while the device was off/dozing must be
 * fired promptly, but never twice, and a recurring task must catch up at most its single latest
 * missed occurrence. The rules are pure ([ScheduleCatchUpPlan]), so they are tested on the JVM with
 * an injected clock and zone, without an Android/WorkManager harness.
 */
class ScheduleCatchUpTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    /** 2026-09-17T17:45:00Z = Thursday 20:45 MSK. */
    private val now = Instant.parse("2026-09-17T17:45:00Z").toEpochMilli()

    private fun recurring(
        hour: Int,
        minute: Int,
        lastRunAt: Long,
        days: Int = ScheduledTask.EVERY_DAY,
        enabled: Boolean = true
    ) = ScheduledTask(
        id = 1,
        prompt = "напоминание",
        hour = hour,
        minute = minute,
        daysMask = days,
        enabled = enabled,
        lastRunAt = lastRunAt,
        chatId = 7
    )

    private fun oneShot(atMs: Long, lastRunAt: Long = 0L, enabled: Boolean = true) = ScheduledTask(
        id = 2,
        prompt = "купить хлеб",
        hour = 0,
        minute = 0,
        daysMask = ScheduledTask.EVERY_DAY,
        enabled = enabled,
        lastRunAt = lastRunAt,
        chatId = 7,
        atMs = atMs
    )

    private fun slot(task: ScheduledTask): Long? = ScheduleCatchUpPlan.missedSlot(task, now, moscow)

    // ---------------------------------------------------------------- one-shot reminders

    @Test
    fun overdueOneShotThatNeverRanIsCaughtUp() {
        val atMs = now - 30 * 60_000L
        assertEquals(atMs, slot(oneShot(atMs)))
    }

    @Test
    fun futureOneShotIsNotCaughtUp() {
        assertNull(slot(oneShot(now + 30 * 60_000L)))
    }

    @Test
    fun oneShotAlreadyRunIsNotCaughtUpAgain() {
        val atMs = now - 30 * 60_000L
        assertNull("an occurrence that already ran must never re-fire", slot(oneShot(atMs, lastRunAt = atMs)))
    }

    @Test
    fun failedOneShotIsNotReFiredForever() {
        // A failed run records lastRunAt = max(now, slot) > atMs (see ScheduleWorker) and keeps its
        // row; the catch-up must not turn that into an endless retry loop.
        val atMs = now - 30 * 60_000L
        assertNull(slot(oneShot(atMs, lastRunAt = now)))
    }

    @Test
    fun disabledOneShotIsNotCaughtUp() {
        assertNull(slot(oneShot(now - 60_000L, enabled = false)))
    }

    // ---------------------------------------------------------------- recurring tasks

    @Test
    fun recurringThatNeverRanIsLeftToTheNormalChain() {
        // Created/armed for its next occurrence; firing the already-passed same-day occurrence would
        // be a surprising, unrequested run. WorkManager still owns it.
        assertNull(slot(recurring(hour = 8, minute = 0, lastRunAt = 0L)))
    }

    @Test
    fun recurringDailyMissingTodaysOccurrenceIsCaughtUp() {
        val yesterday0800 = Instant.parse("2026-09-16T05:00:00Z").toEpochMilli()
        val today0800 = Instant.parse("2026-09-17T05:00:00Z").toEpochMilli()
        assertEquals(today0800, slot(recurring(8, 0, lastRunAt = yesterday0800)))
    }

    @Test
    fun recurringWhoseLatestOccurrenceAlreadyRanIsNotCaughtUp() {
        val yesterday2200 = Instant.parse("2026-09-16T19:00:00Z").toEpochMilli()
        // Daily 22:00: today's 22:00 is still ahead, yesterday's 22:00 already ran.
        assertNull(slot(recurring(22, 0, lastRunAt = yesterday2200)))
    }

    @Test
    fun recurringWeekdayMaskCatchesUpOnlyTheLatestMatchingDay() {
        val mondayOnly = 1 shl 0
        val previousMonday0800 = Instant.parse("2026-09-07T05:00:00Z").toEpochMilli()
        val thisMonday0800 = Instant.parse("2026-09-14T05:00:00Z").toEpochMilli()
        assertEquals(thisMonday0800, slot(recurring(8, 0, lastRunAt = previousMonday0800, days = mondayOnly)))
    }

    @Test
    fun recurringThatRanTheLatestMatchingOccurrenceIsNotCaughtUp() {
        val mondayOnly = 1 shl 0
        val thisMonday0800 = Instant.parse("2026-09-14T05:00:00Z").toEpochMilli()
        assertNull(slot(recurring(8, 0, lastRunAt = thisMonday0800, days = mondayOnly)))
    }

    @Test
    fun recurringWithNoMatchingDayIsNotCaughtUp() {
        assertNull(slot(recurring(8, 0, lastRunAt = now - 3_600_000L, days = 0)))
    }

    @Test
    fun disabledRecurringIsNotCaughtUp() {
        assertNull(slot(recurring(8, 0, lastRunAt = now - 3_600_000L, enabled = false)))
    }

    // ---------------------------------------------------------------- fallback wording

    @Test
    fun missedReminderNotificationIsExplicitlyDelayed() {
        val text = ScheduleRunPlan.missedRunNotificationText("купить хлеб", isOneShot = true, reason = "заряд 3%")
        assertTrue(text.contains("Пропущенное напоминание"))
        assertTrue(text.contains("купить хлеб"))
        assertTrue(text.contains("с задержкой"))
        assertTrue(text.contains("заряд 3%"))
    }

    @Test
    fun missedRecurringNotificationIsExplicitlyDelayed() {
        val text = ScheduleRunPlan.missedRunNotificationText("p", isOneShot = false, reason = null)
        assertTrue(text.contains("Пропущенный запуск по расписанию"))
        assertTrue(text.contains("с задержкой"))
    }
}
