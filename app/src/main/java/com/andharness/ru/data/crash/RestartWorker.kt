package com.andharness.ru.data.crash

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.andharness.ru.bg.WatchdogService
import com.andharness.ru.data.core.WatchdogState
import dagger.hilt.android.EntryPointAccessors

/**
 * Last-resort restart transport (update.md §1.2): when [RestartScheduler] cannot use an alarm it
 * enqueues this one-shot worker, which tries to bring the core back up.
 *
 * Starting a foreground service from a background worker is restricted on Android 12+
 * ([android.app.ForegroundServiceStartNotAllowedException]); the failure is reported honestly
 * through [WatchdogState] instead of being retried in a loop.
 *
 * Power guard (update.md idea #19): bringing the core back up is automatic, not a user request, so
 * under critical power (≤5% and not charging, or thermal status ≥ severe) the restart is deferred by
 * [POWER_DEFER_DELAY_MS] instead of starting a foreground service on a dying phone. The deferral is
 * logged honestly and the guard re-checks on the next delivery, so the core does come back once the
 * device recovers.
 *
 * References:
 * - https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 * - https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()
 */
class RestartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            RestartWorkerEntryPoint::class.java
        )
        val deferReason = entry.powerGuard().deferReason()
        if (deferReason != null) {
            Log.i(
                TAG,
                "crash restart deferred by the power guard: $deferReason " +
                    "(will retry in ${POWER_DEFER_DELAY_MS / 60_000} min)"
            )
            entry.restartScheduler().scheduleWork(POWER_DEFER_DELAY_MS)
            return Result.success()
        }
        val started = WatchdogService.start(applicationContext, REASON)
        if (!started) {
            Log.w(TAG, "WatchdogService could not be started from the restart worker")
            WatchdogState.markStopped(WatchdogState.REASON_START_FAILED)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "Andharness"
        const val REASON = "crash_restart_work"

        /** How long the power guard postpones an automatic post-crash restart. */
        const val POWER_DEFER_DELAY_MS = 15L * 60 * 1000
    }
}
