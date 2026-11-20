package com.andharness.ru.data.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.alarm.ExactAlarm
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates/cancels the [AlarmManager] registration for one [ExactAlarm]. Shared by
 * [com.andharness.ru.data.tools.alarm.AlarmToolsHandler] (user-driven set/cancel) and
 * [BootCompletedReceiver] (re-arm after reboot), so the [PendingIntent] is built identically.
 *
 * Official references:
 * - `AlarmManager.setAlarmClock(AlarmClockInfo, PendingIntent)` — the best-effort, always-exact,
 *   user-visible alarm API; suitable when the user asked for a real alarm:
 *   https://developer.android.com/reference/android/app/AlarmManager#setAlarmClock(android.app.AlarmManager.AlarmClockInfo,%20android.app.PendingIntent)
 * - `AlarmManager.setAndAllowWhileIdle(int, long, PendingIntent)` — the documented fallback when the
 *   app may not schedule exact alarms; it can be delayed by the system/Doze:
 *   https://developer.android.com/reference/android/app/AlarmManager#setAndAllowWhileIdle(int,%20long,%20android.app.PendingIntent)
 * - `AlarmManager.canScheduleExactAlarms()` (API 31+) reflects the SCHEDULE_EXACT_ALARM special
 *   access; Android 14 no longer grants it by default to apps targeting 33+:
 *   https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
 * - `PendingIntent` mutability (FLAG_IMMUTABLE is mandatory on API 31+) and uniqueness by
 *   requestCode: https://developer.android.com/reference/android/app/PendingIntent
 */
@Singleton
class ExactAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: NotificationHelper
) {

    /**
     * Registers [alarm] and returns true when it was scheduled as an EXACT alarm, false when the
     * inexact fallback had to be used.
     */
    fun schedule(alarm: ExactAlarm): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        val pi = pendingIntent(alarm, create = true) ?: return false
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        return if (canExact) {
            try {
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        alarm.atMs,
                        notifications.contentIntent(alarm.chatId, null, alarm.requestCode)
                    ),
                    pi
                )
                true
            } catch (e: SecurityException) {
                // Exact access revoked between the check and the call — degrade honestly.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMs, pi)
                false
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.atMs, pi)
            false
        }
    }

    fun cancel(alarm: ExactAlarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(alarm, create = false) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun pendingIntent(alarm: ExactAlarm, create: Boolean): PendingIntent? {
        val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
            action = ExactAlarmReceiver.ACTION_FIRE
            putExtra(ExactAlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            putExtra(ExactAlarmReceiver.EXTRA_LABEL, alarm.label)
            alarm.chatId?.let { putExtra(ExactAlarmReceiver.EXTRA_CHAT_ID, it) }
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, alarm.requestCode, intent, flags)
    }
}
