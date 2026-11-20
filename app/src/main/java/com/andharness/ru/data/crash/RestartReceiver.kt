package com.andharness.ru.data.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.bg.WatchdogService

/**
 * Manifest-declared, non-exported receiver fired by [RestartScheduler] after a crash (update.md §1.2).
 *
 * It tries to bring the background core back up ([WatchdogService], foreground `specialUse` type).
 * A broadcast delivered from an [android.app.AlarmManager] PendingIntent carries a temporary
 * background-start allowlist, so starting the foreground service here is legitimate; when the OS
 * still refuses (OEM restrictions / inexact path), the service itself records the honest
 * "start_failed" status and the user is expected to open the app.
 *
 * Reference: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTART) return
        val started = WatchdogService.start(context, WatchdogService.REASON_CRASH_RESTART)
        if (!started) {
            Log.w(TAG, "Could not start the watchdog after a crash-restart alarm")
        }
    }

    companion object {
        private const val TAG = "Andharness"
        const val ACTION_RESTART = "com.andharness.ru.action.CRASH_RESTART"
    }
}
