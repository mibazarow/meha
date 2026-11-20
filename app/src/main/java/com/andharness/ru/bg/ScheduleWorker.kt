package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.agent.AgentState
import com.andharness.ru.domain.common.OccurrenceGuard
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduleRunPlan
import com.andharness.ru.domain.schedule.ScheduledTask
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/**
 * Runs one scheduled task: resolves its chat, runs the agent with the stored prompt and records the
 * run. What happens next depends on the task kind ([ScheduledTask.isOneShot]):
 *
 * - RECURRING (the scheduled "cron" path) — notify through the chat gate and re-arm for the next
 *   occurrence, exactly as before.
 * - ONE-SHOT reminder — notify bypassing the chat gate (a reminder must be delivered even while the
 *   user is looking at its chat). Its row is removed ONLY when the run SUCCEEDED (the reminder was
 *   fulfilled); a FAILED run keeps the row visible so the user still sees the unfulfilled reminder.
 *   It is never re-armed either way.
 *
 * Dependencies are pulled from the Hilt graph via an [EntryPoint] so the worker needs no custom
 * WorkerFactory. Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 * Worker lifecycle / results: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started
 */
class ScheduleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.success()
        val slot = inputData.getLong(KEY_SLOT_MS, -1L)
        if (slot <= 0L) return Result.success()

        val entry = EntryPointAccessors.fromApplication(applicationContext, ScheduleWorkerEntryPoint::class.java)
        val repository = entry.scheduleRepository()
        val task = repository.getById(taskId) ?: return Result.success()
        if (!task.enabled) return Result.success()

        // WorkManager is at-least-once: a mid-run process death re-delivers this items. Skip a slot
        // that already ran (fast path), then atomically claim it so two concurrent deliveries cannot
        // both execute the same occurrence. A skipped delivery must NOT re-arm — the delivery that
        // actually ran already did.
        if (OccurrenceGuard.alreadyExecuted(task.lastRunAt, slot)) return Result.success()
        if (!repository.claimRun(taskId, slot)) return Result.success()

        val chats = entry.chatRepository()
        val time = entry.timeProvider()
        val now = time.nowMillis()

        // Bug #4: run the schedule IN the chat it was created from, so the result lands there and the
        // notification deep-links to a chat that actually contains the answer. Only a legacy schedule
        // without a chat (or one whose chat was deleted) gets a fresh chat.
        val chatId = runCatching {
            val originating = task.chatId?.takeIf { chats.observeChat(it).first() != null }
            val fallback = if (originating == null) chats.createChat(titleFor(task.prompt)) else null
            ScheduleRunPlan.targetChatId(originating, fallback)
        }.onFailure { Log.e(TAG, "Resolving the chat for scheduled task $taskId failed", it) }
            .getOrNull()
            ?.takeIf { it > 0L }

        // The agent path is unavailable (no chat could be resolved/created): the run would otherwise
        // be lost silently. Post a plain fallback notification so the user still learns about it —
        // explicitly worded as a delayed/missed delivery — then re-arm a recurring task so its chain
        // does not die with the failed occurrence. A one-shot row is kept (never re-fires).
        if (chatId == null) {
            runCatching {
                entry.notificationHelper().postDoneNotification(
                    text = ScheduleRunPlan.missedRunNotificationText(
                        task.prompt,
                        task.isOneShot,
                        "не удалось подготовить чат для выполнения"
                    ),
                    chatId = task.chatId,
                    notificationId = NotificationHelper.reminderNotificationId(task.id),
                    bypassChatGate = true
                )
            }.onFailure { Log.w(TAG, "Fallback notification for scheduled task $taskId failed", it) }
            if (!task.isOneShot) {
                val fresh = runCatching { repository.getById(taskId) }.getOrNull()
                if (fresh != null && fresh.enabled) {
                    runCatching { entry.scheduleScheduler().schedule(fresh) }
                }
            }
            return Result.success()
        }

        // The run carries the CURRENT local date/time as context, so a fired schedule never has to
        // guess "now" (bug #1 applies to background runs too).
        val message = ScheduleRunPlan.runMessage(task.prompt, now, time.zoneId(), time.locale())

        var failure: String? = null
        try {
            val outcome = entry.agentOrchestrator()
                .run(chatId, message, priority = AgentRunPriority.BACKGROUND)
            if (outcome == AgentRunOutcome.BUSY) {
                failure = "агент уже выполняет другой запрос — запуск пропущен"
            } else {
                // The orchestrator catches its own model/tool failures and writes them honestly into
                // the assistant message; surface that state in the notification text too. Read THIS
                // run's own chat status (not the deprecated viewed-chat shortcut): a background run
                // must never be classified by the last INTERACTIVE chat's outcome.
                when (entry.agentOrchestrator().statusFor(chatId).value.state) {
                    AgentState.ERROR -> failure = "агент завершил с ошибкой (подробности в чате)"
                    AgentState.CANCELLED -> failure = "выполнение отменено"
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled task $taskId failed", e)
            failure = e.message ?: "неизвестная ошибка"
        }

        // Keep the persisted marker at least at the claimed slot (never behind it), so a clock skew
        // cannot make the occurrence look un-run to a re-delivery.
        repository.markRun(taskId, maxOf(System.currentTimeMillis(), slot))

        // Failures must be honest: never leave the chat empty and never stay silent about a failed
        // run — write the reason into the chat and say so in the notification (worded as a reminder
        // for a one-shot, as a scheduled run for a recurring task).
        if (failure != null) {
            val body = if (task.isOneShot) {
                ScheduleRunPlan.reminderFailureMessage(failure)
            } else {
                ScheduleRunPlan.failureMessage(failure)
            }
            runCatching { chats.addMessage(chatId, "assistant", body) }
        }

        if (task.isOneShot) return finishOneShot(task, failure, chatId, repository, entry)

        // RECURRING path — unchanged: notify through the chat gate, then always re-arm for the next
        // occurrence (a failed run must not silently kill the schedule).
        entry.notificationHelper().postDoneNotification(
            ScheduleRunPlan.notificationText(failure),
            chatId
        )

        val fresh = repository.getById(taskId)
        if (fresh != null && fresh.enabled) {
            runCatching { entry.scheduleScheduler().schedule(fresh) }
        }
        return Result.success()
    }

    /**
     * Finishes a ONE-SHOT reminder. It must be delivered even when the user is already looking at
     * [chatId], so the notification bypasses [com.andharness.ru.domain.notify.ChatNotificationGate].
     *
     * The row is removed only when the run SUCCEEDED ([failure] == null): a fulfilled reminder
     * disappears from «Задачи по расписанию». A FAILED run (agent error, busy, cancellation,
     * exception) was NOT delivered, so the row is KEPT visible — the user still sees the unfulfilled
     * reminder and the honest failure in the chat/notification. Nothing here ever re-arms a one-shot,
     * so a kept row will not fire again on its own.
     *
     * Deletion is best effort: if the delete itself fails, the reminder has still been delivered and
     * its claimed `lastRunAt` blocks a second run, but it may linger in the list.
     */
    private suspend fun finishOneShot(
        task: ScheduledTask,
        failure: String?,
        chatId: Long,
        repository: ScheduleRepository,
        entry: ScheduleWorkerEntryPoint
    ): Result {
        entry.notificationHelper().postDoneNotification(
            ScheduleRunPlan.reminderNotificationText(task.prompt, failure),
            chatId,
            notificationId = NotificationHelper.reminderNotificationId(task.id),
            bypassChatGate = true
        )

        if (shouldDeleteOneShotRow(failure)) {
            val deleted = runCatching { repository.delete(task.id) }
                .onFailure { Log.e(TAG, "One-shot reminder ${task.id} could not be deleted after its run", it) }
                .getOrDefault(false)
            if (!deleted) {
                Log.w(
                    TAG,
                    "One-shot reminder ${task.id} was delivered but its row was not deleted; it will " +
                        "not run again (lastRunAt guards the slot) but may linger in the schedule list"
                )
            }
        } else {
            Log.w(TAG, "One-shot reminder ${task.id} failed; keeping the row for the user to see")
        }
        return Result.success()
    }

    private fun titleFor(prompt: String): String =
        "По расписанию: " + prompt.replace('\n', ' ').take(50)

    companion object {
        const val KEY_TASK_ID = "task_id"

        /** The epoch-millis occurrence this work item is for (idempotency; see [OccurrenceGuard]). */
        const val KEY_SLOT_MS = "task_slot_ms"
        private const val TAG = "Andharness"
    }
}

/**
 * Whether a finished ONE-SHOT reminder's row must be removed: only a SUCCESSFUL delivery
 * ([failure] == null) was fulfilled and is deleted. A failed run keeps its row visible, so the user
 * still sees the unfulfilled reminder. Pure, so the rule is covered by a JVM unit test without an
 * Android/WorkManager harness.
 */
internal fun shouldDeleteOneShotRow(failure: String?): Boolean = failure == null
