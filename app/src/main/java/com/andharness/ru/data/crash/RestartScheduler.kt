package com.andharness.ru.data.crash

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the post-crash restart (update.md §1.2).
 *
 * Primary path is [AlarmManager]: `setExactAndAllowWhileIdle` when the app holds the
 * `SCHEDULE_EXACT_ALARM` special access ([AlarmManager.canScheduleExactAlarms], API 31+), otherwise
 * the documented inexact `setAndAllowWhileIdle` fallback. If the alarm manager is unavailable or the
 * call throws, a one-shot [WorkManager] job with the same (short) delay is used as the last resort.
 *
 * The alarm broadcast goes to [RestartReceiver] (manifest-declared, non-exported), which starts
 * [com.andharness.ru.bg.WatchdogService] — restarting a foreground service from an alarm-triggered
 * broadcast is allowed, whereas starting it "in the cold" from a dead process would be blocked by
 * the background-start restrictions.
 *
 * Official references:
 * - https://developer.android.com/reference/android/app/AlarmManager#setExactAndAllowWhileIdle(int,%20long,%20android.app.PendingIntent)
 * - https://developer.android.com/reference/android/app/AlarmManager#setAndAllowWhileIdle(int,%20long,%20android.app.PendingIntent)
 * - https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
 * - https://developer.android.com/topic/libraries/architecture/workmanager
 * - https://developer.android.com/develop/background-work/services/fgs/service-types
 */
@Singleton
class RestartScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Schedules a restart [delayMs] from now. Returns the transport that was actually used. */
    fun schedule(delayMs: Long): Mode {
        val atMs = System.currentTimeMillis() + delayMs.coerceAtLeast(0L)
        return try {
            scheduleAlarm(atMs)
        } catch (e: Exception) {
            Log.e(TAG, "Alarm restart scheduling failed, falling back to WorkManager", e)
            scheduleWork(delayMs)
        }
    }

    /** True when the app may schedule exact alarms (always true below API 31). */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleAlarm(atMs: Long): Mode {
        val am = context.getSystemService(AlarmManager::class.java)
            ?: return scheduleWork(atMs - System.currentTimeMillis())
        val pi = restartPendingIntent()
            ?: return scheduleWork(atMs - System.currentTimeMillis())
        return if (canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            Mode.EXACT_ALARM
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            Mode.INEXACT_ALARM
        }
    }

    /** WorkManager one-shot fallback; returns [Mode.WORK] on success, [Mode.NONE] on failure. */
    fun scheduleWork(delayMs: Long): Mode = try {
        val request = OneTimeWorkRequestBuilder<RestartWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Mode.WORK
    } catch (e: Exception) {
        Log.e(TAG, "WorkManager restart scheduling failed", e)
        Mode.NONE
    }

    /** Cancels a pending restart (alarm and work), e.g. when the user stops the core. */
    fun cancel() {
        try {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = restartPendingIntent(create = false)
            if (am != null && pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cancelling restart alarm failed", e)
        }
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "Cancelling restart work failed", e)
        }
    }

    private fun restartPendingIntent(create: Boolean = true): PendingIntent? {
        val intent = Intent(context, RestartReceiver::class.java).setAction(RestartReceiver.ACTION_RESTART)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** Which transport carried (or will carry) the restart, for honest diagnostics. */
    enum class Mode { EXACT_ALARM, INEXACT_ALARM, WORK, NONE }

    companion object {
        private const val TAG = "Andharness"
        private const val REQUEST_CODE = 7_001
        const val WORK_NAME = "andharness_crash_restart"
    }
}
