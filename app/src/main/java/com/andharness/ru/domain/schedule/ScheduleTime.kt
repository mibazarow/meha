package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.ZoneId

/** Computes the delay until a task's next occurrence, honouring its weekday mask. */
object ScheduleTime {

    /**
     * Milliseconds from [nowMillis] until the next time [task] should run.
     *
     * A one-shot task ([ScheduledTask.atMs] non-null) is a plain difference from [nowMillis],
     * clamped at 0 so an overdue reminder fires immediately; its weekday mask is ignored. A
     * recurring task looks at today first, then the next 7 days, and falls back to 24h if the mask
     * somehow matches nothing. [zone] is the local timezone in which the wall-clock HH:MM is
     * interpreted (injectable for tests).
     */
    fun nextDelayMillis(task: ScheduledTask, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val atMs = task.atMs
        if (atMs != null) return (atMs - nowMillis).coerceAtLeast(0L)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val mask = task.daysMask and ScheduledTask.EVERY_DAY
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            val bit = day.dayOfWeek.value - 1 // Monday=1 -> bit 0
            if ((mask shr bit) and 1 == 0) continue
            val candidate = day.atTime(task.hour, task.minute).atZone(zone).toInstant().toEpochMilli()
            if (candidate > nowMillis + 1_000L) {
                return candidate - nowMillis
            }
        }
        return 24L * 60 * 60 * 1000
    }
}
