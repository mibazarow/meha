package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Periodic safety net for the device-fact cache (update.md §12.2): re-produces every fact that is
 * invalidated or past its TTL, so a missed broadcast cannot leave a stale fact forever.
 *
 * Dependencies are pulled from the Hilt graph via an [EntryPoint] so the worker needs no custom
 * WorkerFactory — same pattern as [ScheduleWorker].
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class DeviceFactsSweepWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            DeviceFactsSweepEntryPoint::class.java
        )
        // Power guard (update.md idea #19): this sweep is non-essential periodic maintenance, so it
        // is skipped under critical power. Returning success (not retry) is honest: the next
        // periodic run will pick the work up again once the device recovers.
        val deferReason = entry.powerGuard().deferReason()
        if (deferReason != null) {
            Log.i(TAG, "Device-fact sweep deferred by the power guard: $deferReason")
            return Result.success()
        }
        return try {
            val refreshed = entry.deviceFactStore().refreshExpired()
            Log.i(TAG, "Device-fact sweep refreshed ${refreshed.size} fact(s)")
            Result.success()
        } catch (e: Exception) {
            // Reported honestly and not retried in a loop: the next periodic run will try again.
            Log.e(TAG, "Device-fact sweep failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
