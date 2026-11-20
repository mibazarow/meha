package com.andharness.ru.domain.schedule

/** Enqueues/cancels the WorkManager job that runs a [ScheduledTask]. */
interface ScheduleScheduler {
    fun schedule(task: ScheduledTask)
    fun cancel(taskId: Long)
    fun rescheduleAll(tasks: List<ScheduledTask>)

    /**
     * Enqueues the task's [com.andharness.ru.bg.ScheduleWorker] for an occurrence whose time already
     * passed ([slotMs]) with no delay, so a run missed while the device was off/dozing is caught up
     * promptly instead of waiting for the next interval.
     *
     * Idempotent by construction: the worker atomically claims [slotMs] against the persisted
     * `lastRunAt`, so calling this repeatedly (or racing the normal WorkManager delivery) cannot
     * execute the same occurrence twice.
     */
    fun scheduleOverdue(task: ScheduledTask, slotMs: Long)
}
