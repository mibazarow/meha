package com.andharness.ru.bg

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.andharness.ru.data.jobs.JobSpec
import com.andharness.ru.data.jobs.JobSpecStore
import com.andharness.ru.data.notifications.NotificationChatTopics
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.AgentRunPriority
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one `job_schedule` job when JobScheduler decides to deliver it.
 *
 * The job id is mapped back to the persisted [JobSpec]: when it carries a `prompt` we start a real
 * agent chat (the same path as the automation `start_chat` action / [ScheduleWorker]) and post a
 * completion notification; otherwise we post the stored `notify_text`.
 *
 * Dependencies are pulled from the Hilt graph through an [EntryPoint] so the service needs no custom
 * factory — the same approach as [ScheduleWorker].
 * Reference: https://developer.android.com/reference/android/app/job/JobService
 */
class AndharnessJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards `jobFinished` so a cancelled coroutine and the normal path cannot both call it. */
    private val finished = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        // No SharedPreferences read (or Hilt graph access) on the main thread: the whole spec
        // resolution + run happens on the scope. `onStartJob` must return promptly.
        scope.launch {
            try {
                val spec = JobSpecStore(applicationContext).get(params.jobId)
                if (spec == null) {
                    // The spec was removed (job_cancel) while the job was already in flight.
                    Log.w(TAG, "no persisted spec for job ${params.jobId} — nothing to run")
                } else {
                    run(spec)
                }
            } catch (e: Exception) {
                Log.e(TAG, "job ${params.jobId} failed", e)
            } finally {
                // false = do not reschedule; a periodic job is re-delivered by the system anyway.
                if (finished.compareAndSet(false, true)) jobFinished(params, false)
            }
        }
        return true
    }

    /**
     * Returns `false`: never reschedule a stopped job. Work is at-least-once, so a still-running
     * coroutine must not have the same job re-delivered on top of it (double execution); the periodic
     * system re-delivery re-arms it on its own cadence.
     * Reference: https://developer.android.com/reference/android/app/job/JobService#onStopJob(android.app.job.JobParameters)
     */
    override fun onStopJob(params: JobParameters): Boolean = false

    override fun onDestroy() {
        // The per-instance scope must not outlive the service (otherwise the coroutine leaks).
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run(spec: JobSpec) {
        val entry = EntryPointAccessors.fromApplication(applicationContext, JobEntryPoint::class.java)
        val prompt = spec.prompt?.trim().orEmpty()
        if (prompt.isNotEmpty()) {
            // Run in the chat the job was created from, so the result lands there and the
            // notification deep-links to the chat that actually contains it. A job without an
            // originating chat gets a fresh chat and we deep-link to THAT new chat — never to a chat
            // that does not hold the result. Mirrors ScheduleWorker's per-task chat resolution.
            val chatId = spec.chatId ?: entry.chatRepository().createChat(titleFor(prompt))
            entry.agentOrchestrator().run(chatId, prompt, priority = AgentRunPriority.BACKGROUND)
            entry.notificationHelper().postDoneNotification(
                text = "Задача по расписанию выполнена",
                chatId = chatId,
                notificationId = notificationId(spec.id),
            )
            return
        }
        val text = spec.notifyText?.trim().orEmpty()
        if (text.isEmpty()) return
        // A legacy job without a chat still gets one, so tapping the notification opens a chat
        // holding the notify text instead of the home screen.
        val chatId = spec.chatId ?: entry.notificationChatRouter().route(
            topic = NotificationChatTopics.SCHEDULE,
            title = NotificationChatTopics.SCHEDULE_TITLE,
            text = text,
        ).takeIf { it > 0L }
        entry.notificationHelper().postDoneNotification(
            text = text,
            chatId = chatId,
            notificationId = notificationId(spec.id),
            title = "Напоминание",
        )
    }

    private fun titleFor(prompt: String): String =
        "По расписанию: " + prompt.replace('\n', ' ').take(50)

    companion object {
        private const val TAG = "AndharnessJobService"

        /** Stable per-job notification id so two jobs never overwrite each other's notification. */
        fun notificationId(jobId: Int): Int =
            NotificationHelper.NOTIFICATION_ID_JOB_BASE +
                (jobId % NotificationHelper.NOTIFICATION_ID_ITEM_MODULUS)
    }
}
