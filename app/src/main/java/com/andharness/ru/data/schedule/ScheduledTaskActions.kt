package com.andharness.ru.data.schedule

import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduleScheduler
import com.andharness.ru.domain.schedule.ScheduledTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared mutation logic for scheduled tasks, used by BOTH the chat-driven tools
 * ([com.andharness.ru.data.tools.schedule.ScheduleToolsHandler]: `schedule_delete` /
 * `schedule_toggle`) and the in-app «Задачи по расписанию» screen. Keeping one implementation means
 * the UI and the agent cancel/replace WorkManager work in exactly the same way.
 *
 * Deleting or disabling must cancel the unique [com.andharness.ru.bg.ScheduleWorker] work item, and
 * re-enabling must (re)enqueue it — see [ScheduleSchedulerImpl].
 */
@Singleton
class ScheduledTaskActions @Inject constructor(
    private val repository: ScheduleRepository,
    private val scheduler: ScheduleScheduler
) {

    /** Enqueues the (enabled) task's work; a no-op inside [ScheduleScheduler] when disabled. */
    fun schedule(task: ScheduledTask) {
        scheduler.schedule(task)
    }

    /** Deletes the row and cancels its WorkManager work. Returns false when the row was absent. */
    suspend fun delete(taskId: Long): Boolean {
        val removed = repository.delete(taskId)
        if (removed) scheduler.cancel(taskId)
        return removed
    }

    /** Sets the enabled flag, then schedules (enabled) or cancels (disabled) the work. */
    suspend fun setEnabled(taskId: Long, enabled: Boolean): Boolean {
        if (!repository.setEnabled(taskId, enabled)) return false
        val fresh = repository.getById(taskId)
        if (fresh != null) {
            if (enabled) scheduler.schedule(fresh) else scheduler.cancel(taskId)
        }
        return true
    }
}
