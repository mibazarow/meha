package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.andharness.ru.data.message.DeferredMessage
import com.andharness.ru.data.message.MessageSendOutcome
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.data.person.PersonRef
import com.andharness.ru.domain.message.MessageChannel
import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit
import com.andharness.ru.data.tools.formatTimestamp

/**
 * One-shot worker that fires a deferred message created by `schedule_message` (update.md §4.8).
 *
 * Rules:
 * - the job is removed from the store **before** the send, so a message is never sent twice and its
 *   text is not kept around after the attempt;
 * - the outcome is posted as a notification **deep-linked to the chat the user asked from**
 *   ([NotificationHelper.contentIntent]) — not to a throwaway chat;
 * - a run that fires late says so explicitly (how late, and the planned time); a failure states the
 *   real reason; nothing is ever reported as delivered unless SMS was confirmed by the platform;
 * - an interaction is recorded in the personal base only when SMS was actually confirmed; the body
 *   is bounded to a gist by [PersonIndexer] and never stored.
 *
 * Dependencies come from the Hilt graph through an [EntryPoint] so the worker needs no custom
 * factory: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 * WorkManager: https://developer.android.com/topic/libraries/architecture/workmanager
 */
class MessageSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getLong(KEY_JOB_ID, -1L)
        if (jobId <= 0L) return Result.success()

        val entry = try {
            EntryPointAccessors.fromApplication(applicationContext, MessageSendWorkerEntryPoint::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable; deferred message $jobId skipped", e)
            return Result.success()
        }
        val store = entry.messageSendStore()
        val job = store.get(jobId) ?: return Result.success()
        // Remove first: a message must never fire twice, and the pending text must not linger.
        store.remove(jobId)

        val now = System.currentTimeMillis()
        val lateMs = now - job.scheduledAt
        val lateNote = if (lateMs > LATE_THRESHOLD_MS) {
            " (с опозданием на ${lateMs / 60_000L} мин, план ${formatTime(job.scheduledAt)})"
        } else {
            ""
        }

        if (!isDeliverable(job)) {
            val text = "Не отправлено ${job.displayName}: цель «${job.target}» стала неразрешимой$lateNote."
            entry.notificationHelper()
                .postDoneNotification(text, job.chatId, notificationId = notificationIdFor(job))
            return Result.success()
        }

        val sender = entry.messageSender()
        val outcome = when (job.channel) {
            MessageChannel.SMS -> sender.sendSms(job.target, job.text)
            MessageChannel.MESSENGER -> sender.openMessenger(job.text, job.targetPackage)
        }

        if (outcome is MessageSendOutcome.Sent) {
            try {
                entry.personIndexer().recordInteraction(
                    channel = InteractionChannel.SMS,
                    direction = InteractionDirection.OUTGOING,
                    at = now,
                    personIdOrIdentity = job.personId?.let { PersonRef.Existing(it) }
                        ?: PersonRef.Identity(IdentityType.PHONE, job.target),
                    summary = job.text,
                    externalRef = "compose:${job.id}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "recording the sent interaction failed", e)
            }
        }

        val report = when (outcome) {
            is MessageSendOutcome.Sent -> "Отправлено ${job.displayName}: ${outcome.detail}$lateNote"
            is MessageSendOutcome.Opened ->
                "Мессенджер для ${job.displayName} открыт, доставка НЕ подтверждена$lateNote. " +
                    "Нужно нажать «Отправить»."
            is MessageSendOutcome.Failed ->
                "Не удалось отправить ${job.displayName}: ${outcome.reason}$lateNote"
        }
        entry.notificationHelper()
            .postDoneNotification(report, job.chatId, notificationId = notificationIdFor(job))
        return Result.success()
    }

    /** `true` when the stored target can still be dialled/opened honestly. */
    private fun isDeliverable(job: DeferredMessage): Boolean = when (job.channel) {
        MessageChannel.SMS -> IdentityNormalizer.normalizePhone(job.target) != null
        MessageChannel.MESSENGER -> job.target.isNotBlank()
    }

    private fun notificationIdFor(job: DeferredMessage): Int =
        NotificationHelper.NOTIFICATION_ID_MESSAGE_BASE +
            (job.id % NotificationHelper.NOTIFICATION_ID_ITEM_MODULUS).toInt()

    private fun formatTime(at: Long): String = formatTimestamp(at, "dd.MM HH:mm")

    companion object {
        const val KEY_JOB_ID = "deferred_message_id"

        private const val TAG = "Andharness"
        private const val LATE_THRESHOLD_MS = 5L * 60_000L
        private const val WORK_PREFIX = "message_send_"

        /**
         * Enqueues the one-shot run of [job], delayed until its scheduled time (at most `0` delay when
         * the time has already passed). Bounded and unique per job so the same job cannot double-fire.
         */
        fun enqueue(context: Context, job: DeferredMessage) {
            val delay = (job.scheduledAt - System.currentTimeMillis()).coerceAtLeast(0L)
            try {
                val request = OneTimeWorkRequestBuilder<MessageSendWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_JOB_ID to job.id))
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "$WORK_PREFIX${job.id}",
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            } catch (e: Exception) {
                Log.e(TAG, "scheduling deferred message ${job.id} failed", e)
            }
        }

        /**
         * Cancels the pending one-shot run of the deferred message [id] (BUG-018).
         *
         * It was enqueued as unique work under `"$WORK_PREFIX<id>"`, so `cancelUniqueWork` removes
         * that work from the scheduler: the worker will not start and the message will not be sent.
         * Cancellation is best-effort — work that is already executing may continue to run
         * (https://developer.android.com/reference/kotlin/androidx/work/WorkManager#canceluniquework).
         * The caller removes the persisted row separately.
         */
        fun cancel(context: Context, id: Long) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_PREFIX$id")
            } catch (e: Exception) {
                Log.e(TAG, "cancelling deferred message $id failed", e)
            }
        }
    }
}
