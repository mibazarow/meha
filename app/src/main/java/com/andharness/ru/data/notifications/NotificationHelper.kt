package com.andharness.ru.data.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import com.andharness.ru.MainActivity
import com.andharness.ru.R
import com.andharness.ru.domain.notify.ChatNotificationGate
import com.andharness.ru.domain.ui.ActiveChatTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val nm: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "NotificationHelper"
        const val CHANNEL_TASKS = "andharness_tasks"
        const val CHANNEL_DONE = "andharness_done"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_MESSAGE_ID = "extra_message_id"

        // -----------------------------------------------------------------------------------------
        // Reserved notification ids — the single source of truth so no two live components collide.
        //
        // Two live foreground services (or a foreground service and a one-shot notification) must
        // never share an id: `NotificationManager.notify`/`startForeground` with the same id would
        // overwrite (and, for `startForeground`, re-bind) the other component's notification.
        // Reference: https://developer.android.com/develop/ui/views/notifications/build-notification
        // -----------------------------------------------------------------------------------------

        /** Fixed ids 1001–1099: one per component that shows a single, well-known notification. */
        const val NOTIFICATION_ID_RUNNING = 1001          // AgentForegroundService
        const val NOTIFICATION_ID_DONE = 1002
        const val FGS_ID_SCREEN_RECORD = 1003             // ScreenRecordService (recording)
        const val FGS_ID_SCREEN_CAPTURE = 1004            // ScreenRecordService (one-frame capture)
        const val FGS_ID_AUDIO_CAPTURE = 1005             // AudioCaptureService
        const val FGS_ID_LOCATION = 1006                  // LocationUpdatesService
        const val FGS_ID_GENERIC = 1007                   // GenericForegroundService
        // 1008 is reserved for CrashGuard's auto-restart-stopped notification (private there).
        const val FGS_ID_WEB = 1009                       // WebEngineService
        const val FGS_ID_SHORT = 1010                     // ShortForegroundService
        const val FGS_ID_CORE = 1011                      // WatchdogService
        const val NOTIFICATION_ID_UNFINISHED = 1012       // WatchdogService (unfinished-task offer)
        const val NOTIFICATION_ID_GEOFENCE = 1013         // GeofenceReceiver
        const val FGS_ID_OVERLAY = 1015                   // OverlayAssistantService (floating bubble)
        const val NOTIFICATION_ID_EMBEDDER_DOWNLOAD = 1016   // EmbedderDownloadWorker (foreground)
        const val NOTIFICATION_ID_SPEECH_MODEL_DOWNLOAD = 1017   // SpeechModelDownloadWorker (foreground)
        const val NOTIFICATION_ID_DEVICE_IMAGE_INDEX = 1018   // DeviceImageIndexWorker (foreground)
        const val NOTIFICATION_ID_DEVICE_DOCUMENT_INDEX = 1019 // DeviceDocumentIndexWorker (foreground)

        /**
         * Derived per-item id ranges (base + item id % [NOTIFICATION_ID_ITEM_MODULUS]). Placed well
         * above every other component's range so they can never overlap the fixed ids above or the
         * ranges owned by ExactAlarmReceiver (3000+) and NumberChatManager (8000+).
         */
        const val NOTIFICATION_ID_MESSAGE_BASE = 200_000
        const val NOTIFICATION_ID_AUTOMATION_BASE = 300_000
        const val NOTIFICATION_ID_JOB_BASE = 400_000
        /** One-shot reminders; 500_000–599_999, keyed by schedule/task id (see [reminderNotificationId]). */
        const val NOTIFICATION_ID_REMINDER_BASE = 500_000
        /**
         * Chat-targeted "done" notifications; 600_000–699_999, keyed by (chat, message, base id).
         * Sits above the reminder range and below `Int.MAX_VALUE`, so it can never collide with the
         * fixed ids 1001–1019, the 200_000/300_000/400_000/500_000 derived ranges, ExactAlarmReceiver
         * (3000+) or NumberChatManager (8000+).
         */
        const val NOTIFICATION_ID_CHAT_BASE = 600_000

        /** Width of each derived range; item ids wrap modulo this value. */
        const val NOTIFICATION_ID_ITEM_MODULUS = 100_000

        /**
         * Stable notification id of the ONE-SHOT reminder backed by task [taskId]. Its own range
         * means a reminder can never overwrite a message/automation/job notification or a fixed id.
         */
        fun reminderNotificationId(taskId: Long): Int =
            NOTIFICATION_ID_REMINDER_BASE + (taskId % NOTIFICATION_ID_ITEM_MODULUS).toInt()

        /**
         * Stable notification id for a chat-targeted "done" notification. A `null` chat returns
         * [baseId] EXACTLY, so global notifications (speech-model download 1017, …) keep
         * their fixed ids. Otherwise the id is folded into the reserved 600_000–699_999 range, so
         * two chats (or two messages/callers) never overwrite each other's notification in the
         * shade. `NotificationManager.notify` replaces a notification whose id matches, so a shared
         * id would make the last poster's content win.
         * https://developer.android.com/develop/ui/views/notifications/build-notification
         *
         * Pure (no `Context`) and deterministic, therefore unit-testable on the JVM.
         */
        fun notificationIdFor(chatId: Long?, messageId: Long?, baseId: Int): Int {
            if (chatId == null) return baseId
            var h = (chatId xor (chatId ushr 32)).toInt()
            if (messageId != null) h = h * 31 + (messageId xor (messageId ushr 32)).toInt()
            h = h * 31 + baseId
            val slot = ((h.toLong() and 0x7fffffffL) % NOTIFICATION_ID_ITEM_MODULUS).toInt()
            return NOTIFICATION_ID_CHAT_BASE + slot
        }

        /**
         * Stable per-notification request code so two notifications never share [PendingIntent]
         * extras. Pure (no instance state) so it is unit-testable without an Android `Context`.
         * Returns [fallback] when there is no chat (the "open the app, no specific chat" case);
         * otherwise a distinct code per (chat, message) so each notification's deep link is unique.
         */
        fun requestCodeFor(chatId: Long?, messageId: Long?, fallback: Int): Int {
            if (chatId == null) return fallback
            var code = (chatId xor (chatId ushr 32)).toInt()
            if (messageId != null) code = code * 31 + (messageId xor (messageId ushr 32)).toInt()
            return if (code == 0) fallback else code
        }
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val tasksChannel = NotificationChannel(
                CHANNEL_TASKS,
                "Меха работает",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Текущая выполняемая задача"
            }

            val doneChannel = NotificationChannel(
                CHANNEL_DONE,
                "Меха завершил",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Сообщение о завершении задачи"
            }

            nm.createNotificationChannel(tasksChannel)
            nm.createNotificationChannel(doneChannel)
        }
    }

    fun runningNotification(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText("Работаю над задачей…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun doneNotification(
        text: String,
        chatId: Long? = null,
        messageId: Long? = null,
        title: String = "Меха",
        /**
         * [PendingIntent] request code for the deep link. Defaults to [NOTIFICATION_ID_DONE] so
         * direct callers keep the old behaviour; [postDoneNotification] derives a distinct code via
         * [requestCodeFor] (`chatId, messageId, notificationId`) so two notifications (e.g. two
         * chats) never share [PendingIntent] extras and each opens its own chat.
         */
        requestCode: Int = NOTIFICATION_ID_DONE
    ): Notification {
        ensureChannels()
        val pendingIntent = contentIntent(chatId, messageId, requestCode)

        return NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Posts a chat-related "done/result/reminder" notification through the single suppression gate
     * for update.md bug #5.
     *
     * The notification is NOT posted when the app is in the foreground AND [chatId] is the chat the
     * user currently has open — it would be redundant noise. Non-chat notifications ([chatId] == null,
     * e.g. the watchdog's unfinished-task offer) are never suppressed, and foreground-service
     * notifications do not use this method at all. Everything else (background/closed app, another
     * screen, another chat) still posts.
     *
     * [bypassChatGate] opts a caller out of the suppression entirely — reserved for ONE-SHOT
     * reminders, which must still interrupt the user when the firing chat is open (default false).
     *
     * @return true when the notification was actually posted, false when the gate suppressed it, so
     *   callers (notably the `notification_post` tool) can report the real outcome instead of lying.
     */
    fun postDoneNotification(
        text: String,
        chatId: Long? = null,
        messageId: Long? = null,
        notificationId: Int = NOTIFICATION_ID_DONE,
        title: String = "Меха",
        /**
         * When true, [ChatNotificationGate] is NOT applied: the notification is posted even while
         * the user is looking at the very chat it targets. Used by ONE-SHOT reminders, whose whole
         * point is to interrupt the user at a chosen moment regardless of what is on screen.
         * Defaults to false, so every existing caller keeps the bug-#5 suppression behaviour.
         */
        bypassChatGate: Boolean = false
    ): Boolean {
        // A chat-targeted notification gets its own id so two chats never overwrite each other in
        // the shade; a chat-less one keeps the caller's base id exactly (global notifications).
        val targetId = notificationIdFor(chatId, messageId, notificationId)
        if (!bypassChatGate && ChatNotificationGate.shouldSuppress(
                appForeground = ActiveChatTracker.isAppForeground,
                activeChatId = ActiveChatTracker.activeChatId,
                targetChatId = chatId,
            )
        ) {
            Log.i(
                TAG,
                "Notification suppressed (chat $chatId is open in the foreground); id=$targetId",
            )
            return false
        }
        return try {
            NotificationManagerCompat.from(context)
                .notify(
                    targetId,
                    // PendingIntents are identified by (Intent.filterEquals, requestCode): extras are
                    // NOT part of identity, so with FLAG_UPDATE_CURRENT a shared request code makes
                    // the LAST poster's extras win and every notification deep-links to the same
                    // chat. A distinct code per (chat, message) keeps each deep link its own.
                    // https://developer.android.com/reference/android/app/PendingIntent
                    doneNotification(
                        text,
                        chatId,
                        messageId,
                        title,
                        requestCode = requestCodeFor(chatId, messageId, notificationId),
                    )
                )
            true
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS denied (Android 13+, API 33+) or notifications disabled.
            false
        }
    }

    /**
     * Builds the content [PendingIntent] that opens [MainActivity] on a SPECIFIC chat (SPEC §20).
     *
     * - Explicit [Intent] with the `chat_id` (and optional `message_id`) extras.
     * - A UNIQUE `requestCode` per notification/chat, otherwise [PendingIntent] extras are reused
     *   and every notification would open the same chat.
     * - [PendingIntent.FLAG_IMMUTABLE] (mandatory for Android 12+, API 31+) and
     *   [PendingIntent.FLAG_UPDATE_CURRENT] so newer extras replace older ones.
     * - [TaskStackBuilder] so the back stack is synthesised correctly on cold start.
     * - `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP` (NOT `CLEAR_TOP`): together with
     *   `MainActivity`'s `launchMode="singleTask"` the already-running instance receives
     *   `onNewIntent` and opens the chat. `CLEAR_TOP` used to finish + recreate the activity, which
     *   restored its saved state (null `savedInstanceState`) and silently dropped the deep link.
     *   https://developer.android.com/guide/components/activities/tasks-and-back-stack#HandlingAffinity
     *   https://developer.android.com/guide/topics/manifest/activity-element#lmode
     *
     * When [chatId] is null the intent simply opens the app (the chat list fallback for
     * notifications that carry no chat).
     *
     * References: https://developer.android.com/reference/android/app/PendingIntent ,
     * https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability ,
     * https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_SINGLE_TOP ,
     * https://developer.android.com/reference/androidx/core/app/TaskStackBuilder
     */
    fun contentIntent(chatId: Long?, messageId: Long?, requestCode: Int): PendingIntent {
        val openMain = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (chatId != null) putExtra(EXTRA_CHAT_ID, chatId)
            if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(openMain)
            getPendingIntent(
                requestCode,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } ?: PendingIntent.getActivity(
            // TaskStackBuilder still returns non-null in practice; the plain activity PI is a
            // defensive fallback that keeps the notification tappable no matter what.
            context,
            requestCode,
            openMain,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

}
