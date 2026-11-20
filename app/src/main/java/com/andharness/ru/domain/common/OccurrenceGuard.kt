package com.andharness.ru.domain.common

/**
 * Pure helper for the "one occurrence, one execution" guard shared by the self-chaining
 * WorkManager cron workers ([com.andharness.ru.bg.ScheduleWorker] and
 * [com.andharness.ru.bg.AutomationTimeWorker]).
 *
 * WorkManager is **at-least-once**: a mid-run process death makes the OS re-deliver the same work
 * item, which without a guard would run the same prompt/rule twice. Each work request therefore
 * carries the exact occurrence it is for ([slotMillis], persisted in `inputData`), and the worker
 * records the last executed slot in the database. A re-delivery of the same occurrence is skipped.
 *
 * The decision here is pure and unit-testable; the atomic "compare-and-set" itself lives in SQL
 * (`UPDATE ... WHERE lastRunAt < :slot`), so two concurrent deliveries cannot both claim a slot.
 *
 * Reference: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/handle-worker-stopping
 */
object OccurrenceGuard {

    /**
     * The epoch-millis instant a run scheduled with [delayMillis] from [nowMillis] is for. Used both
     * when arming the work (the scheduler) and when deciding whether to run it (the worker), so the
     * value is stable across a re-delivery because it travels in the persisted `inputData`.
     */
    fun slotMillis(nowMillis: Long, delayMillis: Long): Long = nowMillis + delayMillis

    /**
     * True when [slotMillis] must be skipped because it (or a later occurrence) already ran, judged
     * by the persisted [lastRunAt]. A `null` (never run) or earlier timestamp means "not yet run".
     */
    fun alreadyExecuted(lastRunAt: Long?, slotMillis: Long): Boolean =
        lastRunAt != null && lastRunAt >= slotMillis
}
