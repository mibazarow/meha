package com.andharness.ru.data.schedule

import android.util.Log
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.notifications.NotificationChatRouter
import com.andharness.ru.data.notifications.NotificationChatTopics
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.schedule.ScheduleCatchUpPlan
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduleRunPlan
import com.andharness.ru.domain.schedule.ScheduleScheduler
import com.andharness.ru.domain.schedule.ScheduledTask
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup reconciliation for scheduled tasks/reminders (SPEC §20): fires the runs that were missed
 * while the device was off/dozing (or after a dropped WorkManager delivery), instead of silently
 * waiting for the next interval.
 *
 * Invoked from [com.andharness.ru.AndharnessApplication.onCreate], which runs whenever the app
 * process starts — including when the OS starts it to deliver a `BOOT_COMPLETED` broadcast to one of
 * the manifest receivers, or to run a persisted WorkManager job. It reuses the existing
 * [ScheduleScheduler]/[ScheduleWorker] and the existing `lastRunAt` state, so no schema change and no
 * second scheduler are needed.
 *
 * Safety properties, all required:
 * - **BOUNDED** — at most [MAX_CATCH_UPS] tasks are considered per start, so a long gap cannot cause
 *   an unbounded burst of agent runs on startup.
 * - **IDEMPOTENT** — the worker atomically claims the occurrence against `lastRunAt`
 *   ([ScheduleRepository.claimRun]) and the work is enqueued under the task's unique work name, so a
 *   repeated reconcile (or a race with the normal delivery) cannot execute an occurrence twice.
 * - **POWER-AWARE** — a bulk catch-up never fires heavy agent work when [PowerGuard] says the device
 *   must defer (<=5% battery and not charging, or severe/critical thermal status). The reminder is
 *   NOT claimed in that case, so a later start retries it once power allows; meanwhile a plain
 *   fallback notification tells the user it was delayed.
 * - **NEVER CRASHES STARTUP** — the whole reconcile and each individual task are wrapped in
 *   `runCatching`; a failure is logged and skipped.
 *
 * References:
 * - WorkManager (persisted, at-least-once work / rescheduling): https://developer.android.com/topic/libraries/architecture/workmanager
 * - `BOOT_COMPLETED` and starting the app process for a manifest receiver: https://developer.android.com/guide/components/broadcasts
 * - Notifications (fallback delivery): https://developer.android.com/develop/ui/views/notifications/build-notification
 */
@Singleton
class ScheduleCatchUp @Inject constructor(
    private val repository: ScheduleRepository,
    private val scheduler: ScheduleScheduler,
    private val notifications: NotificationHelper,
    private val notificationChatRouter: NotificationChatRouter,
    private val timeProvider: TimeProvider,
    private val powerGuard: PowerGuard
) {

    /**
     * Fires up to [limit] missed occurrences. Returns how many were actually enqueued; a power
     * deferral or a failure is reported (notification/log) but does not abort the rest.
     */
    suspend fun reconcile(limit: Int = MAX_CATCH_UPS): Int {
        val now = timeProvider.nowMillis()
        val zone = timeProvider.zoneId()

        val missed = runCatching { repository.getAll() }
            .onFailure { Log.w(TAG, "Reading schedules for catch-up failed: ${it.message}") }
            .getOrDefault(emptyList())
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { task ->
                ScheduleCatchUpPlan.missedSlot(task, now, zone)?.let { slot -> task to slot }
            }
            .take(limit)
            .toList()

        if (missed.isEmpty()) return 0

        var fired = 0
        for ((task, slot) in missed) {
            val enqueued = runCatching { fire(task, slot) }
                .onFailure { Log.w(TAG, "Catch-up for schedule #${task.id} failed: ${it.message}") }
                .getOrDefault(false)
            if (enqueued) fired++
        }
        return fired
    }

    /** Enqueues one caught-up occurrence, unless the power guard forbids the heavy agent run now. */
    private suspend fun fire(task: ScheduledTask, slotMs: Long): Boolean {
        if (powerGuard.shouldDefer()) {
            val reason = powerGuard.deferReason()
            Log.i(TAG, "Catch-up for schedule #${task.id} deferred by PowerGuard: $reason")
            notifyMissed(task, reason)
            return false
        }
        scheduler.scheduleOverdue(task, slotMs)
        return true
    }

    /**
     * Plain fallback notification for a run that could not be delivered now. Same per-task
     * notification id as the reminder path, so repeated reconciles replace rather than stack.
     */
    private suspend fun notifyMissed(task: ScheduledTask, reason: String?) {
        val text = ScheduleRunPlan.missedRunNotificationText(task.prompt, task.isOneShot, reason)
        runCatching {
            // A legacy task without a chat still gets one, so the missed-run notification opens a
            // chat holding the text rather than the home screen.
            val chatId = notificationChatRouter.routeIfNeeded(
                topic = NotificationChatTopics.SCHEDULE,
                title = NotificationChatTopics.SCHEDULE_TITLE,
                text = text,
                chatId = task.chatId,
            ) ?: task.chatId
            notifications.postDoneNotification(
                text = text,
                chatId = chatId,
                notificationId = NotificationHelper.reminderNotificationId(task.id),
                // A missed reminder must reach the user regardless of which chat is open.
                bypassChatGate = true
            )
        }.onFailure { Log.w(TAG, "Missed-run notification for schedule #${task.id} failed: ${it.message}") }
    }

    companion object {
        /** Upper bound of catch-up runs per app/process start. */
        const val MAX_CATCH_UPS = 5
        private const val TAG = "Andharness"
    }
}
