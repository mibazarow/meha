package com.andharness.ru.data.schedule

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.andharness.ru.bg.ScheduleWorker
import com.andharness.ru.domain.common.OccurrenceGuard
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.schedule.ScheduleScheduler
import com.andharness.ru.domain.schedule.ScheduleTime
import com.andharness.ru.domain.schedule.ScheduledTask
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules each task as a unique one-time [ScheduleWorker] delayed to its next occurrence; the
 * worker re-enqueues itself after running (self-chaining), which gives exact-to-the-minute,
 * weekday-aware scheduling that PeriodicWorkRequest (fixed 15-min minimum) cannot express.
 *
 * Reference: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started
 */
@Singleton
class ScheduleSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) : ScheduleScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun schedule(task: ScheduledTask) {
        if (!task.enabled) {
            cancel(task.id)
            return
        }
        val now = timeProvider.nowMillis()
        val delay = ScheduleTime.nextDelayMillis(task, now, timeProvider.zoneId())
        // The exact occurrence this work item is for; carried in the persisted inputData so a
        // re-delivery after a process death can be recognised and skipped (WorkManager is
        // at-least-once). See [com.andharness.ru.domain.common.OccurrenceGuard].
        val slot = OccurrenceGuard.slotMillis(now, delay)
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ScheduleWorker.KEY_TASK_ID to task.id,
                    ScheduleWorker.KEY_SLOT_MS to slot
                )
            )
            .addTag(tag(task.id))
            .build()
        workManager.enqueueUniqueWork(uniqueName(task.id), ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Catch-up path: arm the SAME unique [ScheduleWorker], but for an occurrence ([slotMs]) that is
     * already due, with no initial delay. The slot travels in `inputData` so the worker's atomic
     * claim ([OccurrenceGuard]) keeps the run idempotent even if this races the normal delivery.
     */
    override fun scheduleOverdue(task: ScheduledTask, slotMs: Long) {
        if (!task.enabled) {
            cancel(task.id)
            return
        }
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInputData(
                workDataOf(
                    ScheduleWorker.KEY_TASK_ID to task.id,
                    ScheduleWorker.KEY_SLOT_MS to slotMs
                )
            )
            .addTag(tag(task.id))
            .build()
        workManager.enqueueUniqueWork(uniqueName(task.id), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(taskId: Long) {
        workManager.cancelUniqueWork(uniqueName(taskId))
    }

    override fun rescheduleAll(tasks: List<ScheduledTask>) {
        tasks.forEach { schedule(it) }
    }

    private fun uniqueName(taskId: Long) = "andharness_schedule_$taskId"
    private fun tag(taskId: Long) = "andharness_schedule_tag_$taskId"
}
