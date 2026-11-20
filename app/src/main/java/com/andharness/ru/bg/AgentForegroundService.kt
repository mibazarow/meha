package com.andharness.ru.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.agent.ForegroundRunTracker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AgentForegroundService : Service() {

    @Inject
    lateinit var notifications: NotificationHelper

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
        // Explicit type: Android 14+ (API 34+) requires the runtime type to match the manifest
        // declaration (`android:foregroundServiceType="dataSync"`). ServiceCompat keeps the call
        // correct on older releases.
        // Reference: https://developer.android.com/about/versions/14/changes/fgs-types-required
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_ID_RUNNING,
            notifications.runningNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Задача завершена"
            val chatId = intent.getLongExtra(NotificationHelper.EXTRA_CHAT_ID, -1L).takeIf { it != -1L }
            // The per-turn "done" notification is normally posted (its id is separate from the
            // ongoing one), but the foreground service itself is torn down only when the LAST run of
            // the process has finished. With concurrent per-chat runs a sibling turn may still be in
            // flight, and stopping here would drop its foreground protection.
            //
            // postNotification is false when a user-initiated run already brought the originating
            // chat back to the front: the report is on screen, so the chat-targeted "done" notice
            // would be redundant noise. It stays true (the fallback) for every other case.
            if (intent.getBooleanExtra(EXTRA_POST_NOTIFICATION, true)) {
                notifications.postDoneNotification(message, chatId)
            }
            if (!intent.getBooleanExtra(EXTRA_KEEP_ALIVE, false)) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_STOP = "stop"
        const val EXTRA_MESSAGE = "message"

        /**
         * When true, a stop request posts its "done" notification but leaves the SHARED foreground
         * service running because other chat turns are still in flight (see [ForegroundRunTracker]).
         */
        const val EXTRA_KEEP_ALIVE = "keep_alive"

        /**
         * When false, [stop] tears the service down but does NOT post the per-turn "done"
         * notification, because the caller already returned the user to the chat on screen.
         * Defaults to true so every other caller keeps the existing notification behaviour.
         */
        const val EXTRA_POST_NOTIFICATION = "post_notification"

        /** Process-scoped set of chats currently holding the shared foreground service. */
        private val foregroundRuns = ForegroundRunTracker()

        /**
         * Starts (or keeps) the shared foreground service for [chatId]'s turn. Idempotent per chat.
         * Reference: https://developer.android.com/develop/background-work/services/fgs
         */
        fun start(context: Context, chatId: Long) {
            foregroundRuns.add(chatId)
            ContextCompat.startForegroundService(context, Intent(context, AgentForegroundService::class.java))
        }

        /**
         * Reports that [chatId]'s turn ended. The "done" notification is posted for it; the shared
         * service is stopped only when no other chat's turn still needs it.
         */
        fun stop(
            context: Context,
            message: String = "Задача завершена",
            chatId: Long? = null,
            postNotification: Boolean = true,
        ) {
            // chatId is always supplied by the agent paths; the null fallback (legacy callers) is
            // treated as "no other run holds it", i.e. stop as before.
            val keepAlive = chatId != null && !foregroundRuns.remove(chatId)
            val i = Intent(context, AgentForegroundService::class.java)
                .putExtra(EXTRA_STOP, true)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_KEEP_ALIVE, keepAlive)
                .putExtra(EXTRA_POST_NOTIFICATION, postNotification)
            if (chatId != null) i.putExtra(NotificationHelper.EXTRA_CHAT_ID, chatId)
            context.startService(i)
        }
    }
}
