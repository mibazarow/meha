package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.ZoneId

/**
 * Pure decision helper for the "missed run" catch-up ([com.andharness.ru.data.schedule.ScheduleCatchUp]).
 *
 * WorkManager persists its one-time work across reboot and normally delivers an overdue job, but the
 * chain can be lost (force-stop, a dropped delivery) and a device that was off/dozing can leave a
 * scheduled run with no delivery at all. This helper answers, for one [ScheduledTask], WHICH single
 * occurrence (if any) must be fired promptly to catch up — without inventing a second scheduler and
 * without a DB schema change (it only reads the existing `atMs` / `lastRunAt` / `hour` / `minute` /
 * `daysMask` state).
 *
 * Rules:
 * - a ONE-SHOT reminder ([ScheduledTask.isOneShot]) is missed when its instant [ScheduledTask.atMs]
 *   is already in the past and it has not run yet (`lastRunAt < atMs`); it never re-fires after a run
 *   (a failed run also records `lastRunAt`, so the user sees it once, not in a loop);
 * - a RECURRING task is missed only when its most recent PAST occurrence is strictly later than
 *   [ScheduledTask.lastRunAt] — i.e. the occurrence that should have run next did not. At most ONE
 *   occurrence is returned, so a long gap cannot cause a burst of back-to-back runs;
 * - a recurring task that has NEVER run (`lastRunAt <= 0`) is deliberately not caught up: it was
 *   armed for its next occurrence when it was created/edited, and firing the already-passed
 *   occurrence of that same day would be a surprising, unrequested run. The normal WorkManager chain
 *   remains responsible for it.
 *
 * Pure `java.time` only (no Android), so the rules are unit-tested on the JVM with an injected clock
 * and zone. References:
 * - `Instant` / `ZonedDateTime` / weekday arithmetic: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/package-summary.html
 * - WorkManager rescheduling semantics: https://developer.android.com/topic/libraries/architecture/workmanager
 */
object ScheduleCatchUpPlan {

    /**
     * The epoch-millis occurrence that must be fired now to catch up [task], or `null` when nothing
     * was missed. Deterministic: [nowMillis] and [zone] are injected.
     */
    fun missedSlot(task: ScheduledTask, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!task.enabled) return null

        val atMs = task.atMs
        if (atMs != null) {
            return if (atMs <= nowMillis && task.lastRunAt < atMs) atMs else null
        }

        if (task.lastRunAt <= 0L) return null
        val occurrence = mostRecentPastOccurrence(task, nowMillis, zone) ?: return null
        return if (task.lastRunAt < occurrence) occurrence else null
    }

    /**
     * The latest wall-clock occurrence ([ScheduledTask.hour]:[ScheduledTask.minute]) matching
     * [ScheduledTask.daysMask] that is at or before [nowMillis], looked back over the last week, or
     * `null` when the mask matches no day or the task's clock cannot be parsed. The local wall clock
     * is resolved in [zone] (so DST transitions are handled by `java.time`).
     */
    private fun mostRecentPastOccurrence(task: ScheduledTask, nowMillis: Long, zone: ZoneId): Long? {
        val mask = task.daysMask and ScheduledTask.EVERY_DAY
        if (mask == 0) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        for (offset in 0..7) {
            val day = now.toLocalDate().minusDays(offset.toLong())
            val bit = day.dayOfWeek.value - 1 // Monday=1 -> bit 0
            if ((mask shr bit) and 1 == 0) continue
            val candidate = day.atTime(task.hour, task.minute).atZone(zone).toInstant().toEpochMilli()
            if (candidate <= nowMillis) return candidate
        }
        return null
    }
}
