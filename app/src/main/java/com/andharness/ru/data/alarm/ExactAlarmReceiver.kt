package com.andharness.ru.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.data.notifications.NotificationChatRouter
import com.andharness.ru.data.notifications.NotificationChatTopics
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires one persisted exact alarm: marks the row `fired = true` and posts a notification through
 * [NotificationHelper] (deep-linked to the originating chat when one was recorded).
 *
 * Declared in the manifest as a non-exported receiver; the only sender is this app's own
 * `AlarmManager` [android.app.PendingIntent]. Dependencies are injected by Hilt in `onReceive`.
 *
 * Reference: https://developer.android.com/develop/background-work/services/alarms/schedule
 */
@AndroidEntryPoint
class ExactAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: ExactAlarmRepository

    @Inject
    lateinit var notifications: NotificationHelper

    @Inject
    lateinit var notificationChatRouter: NotificationChatRouter

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (id <= 0L) return
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L).takeIf { it > 0L }

        // A receiver has ~10s of main-thread budget; do the Room write + notification off-thread and
        // keep the process alive until it is done.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                repository.markFired(id, true)
                val text = if (label.isBlank()) {
                    "Будильник #$id сработал."
                } else {
                    "Будильник: $label"
                }
                // An alarm without an originating chat still gets one, so tapping it opens a chat
                // holding the alarm text instead of the home screen.
                val targetChatId = if (chatId != null) {
                    chatId
                } else {
                    notificationChatRouter.route(
                        topic = NotificationChatTopics.ALARM,
                        title = NotificationChatTopics.ALARM_TITLE,
                        text = text,
                    ).takeIf { it > 0L }
                }
                notifications.postDoneNotification(
                    text = text,
                    chatId = targetChatId,
                    notificationId = NOTIFICATION_ID_BASE + (id % 100000).toInt()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exact alarm $id failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.andharness.ru.action.EXACT_ALARM_FIRE"
        const val EXTRA_ALARM_ID = "exact_alarm_id"
        const val EXTRA_LABEL = "exact_alarm_label"
        const val EXTRA_CHAT_ID = "exact_alarm_chat_id"
        private const val NOTIFICATION_ID_BASE = 3000
        private const val TAG = "Andharness"
    }
}
