package com.andharness.ru.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms pending exact alarms after a device reboot: [android.app.AlarmManager] alarms do not
 * survive a reboot, but the rows in Room do, so every not-yet-fired alarm still in the future is
 * registered again through [ExactAlarmScheduler] (which itself falls back honestly to an inexact
 * alarm when SCHEDULE_EXACT_ALARM is not granted — a no-op for already-past rows).
 *
 * Reference: https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var repository: ExactAlarmRepository

    @Inject
    lateinit var scheduler: ExactAlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                repository.getAll()
                    .filter { !it.fired && it.atMs > now }
                    .forEach { runCatching { scheduler.schedule(it) } }
            } catch (e: Exception) {
                Log.e(TAG, "Re-registering exact alarms after boot failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
