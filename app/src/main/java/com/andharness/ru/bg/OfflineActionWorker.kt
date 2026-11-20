package com.andharness.ru.bg

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andharness.ru.data.offline.OfflineActionQueue
import com.andharness.ru.data.offline.OfflineQueueDrain
import com.andharness.ru.domain.tools.ToolRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * Drains the persisted [OfflineActionQueue]: it re-invokes each queued tool call through
 * [ToolRegistry.execute] — the SAME dispatch path the agent uses — when the device has network
 * again (§2 "Очередь действий offline").
 *
 * It carries a `NetworkType.CONNECTED` constraint, so WorkManager only starts it once connectivity
 * is back and re-delivers it after the constraint is (re)met. On top of that the drain itself stops
 * as soon as it observes the device offline ([OfflineQueueDrain.drain]), and the worker asks for a
 * bounded exponential-backoff retry while items remain — the per-item attempt budget lives in
 * [OfflineQueueDrain.MAX_ATTEMPTS], so a permanently-failing action is eventually dropped instead
 * of retrying forever.
 *
 * Dependencies are pulled from the Hilt graph via an [EntryPoint], so the worker needs no custom
 * WorkerFactory — the same pattern as `ScheduleWorker`/`MemoryGcWorker`.
 *
 * References:
 * - Constraints / `NetworkType.CONNECTED`:
 *   https://developer.android.com/reference/androidx/work/Constraints.Builder#setRequiredNetworkType(androidx.work.NetworkType)
 *   https://developer.android.com/reference/androidx/work/NetworkType#CONNECTED
 * - Backoff (`BackoffPolicy`, `setBackoffCriteria`) and `Result.retry`:
 *   https://developer.android.com/reference/androidx/work/WorkRequest.Builder#setBackoffCriteria(androidx.work.BackoffPolicy,%20long,%20java.util.concurrent.TimeUnit)
 *   https://developer.android.com/reference/androidx/work/ListenableWorker.Result#retry()
 * - `ConnectivityManager.getNetworkCapabilities` / `NetworkCapabilities.NET_CAPABILITY_INTERNET`:
 *   https://developer.android.com/reference/android/net/ConnectivityManager#getNetworkCapabilities(android.net.Network)
 *   https://developer.android.com/reference/android/net/NetworkCapabilities
 * - Hilt in workers (EntryPoint, no WorkerFactory):
 *   https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class OfflineActionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            OfflineActionWorkerEntryPoint::class.java
        )
        val queue = entry.offlineActionQueue()
        if (queue.count() == 0) return Result.success()

        val registry = entry.toolRegistry()
        val report = OfflineQueueDrain.drain(
            queue = queue,
            isOnline = { isOnline(applicationContext) },
            execute = { name, args -> registry.execute(name, args) }
        )
        // The suffix must be honest: `stoppedOffline` is set both when the device really lost
        // connectivity AND when a tool failed with a network-like error while we were online
        // (OfflineQueueDrain.kt). So we judge the wording on the REAL connectivity state.
        val online = isOnline(applicationContext)
        val suffix = when {
            !report.stoppedOffline -> ""
            online -> "(stopped early: network-like error, device online)"
            else -> "(offline)"
        }
        Log.i(
            TAG,
            "Offline queue drain: attempted=${report.attempted} succeeded=${report.succeeded} " +
                "failed=${report.failed} exhausted=${report.exhausted} remaining=${report.remaining} " +
                suffix
        )
        // Items left → retry later (with backoff). Empty → done. Either way the per-item attempt
        // budget guarantees termination: a doomed action is dropped after MAX_ATTEMPTS.
        return if (report.remaining > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "Andharness"

        /** Unique work name so repeated scheduling never stacks duplicate drains. */
        const val WORK_NAME = "andharness_offline_queue_drain"

        /** Exponential backoff between drain retries. */
        private const val BACKOFF_SECONDS = 30L

        /**
         * Schedules (or keeps) the single drain job. Safe to call whenever the queue becomes
         * non-empty and at app start: [ExistingWorkPolicy.KEEP] means an in-flight drain is never
         * restarted, while a finished one is replaced by a fresh run.
         */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<OfflineActionWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_SECONDS,
                        TimeUnit.SECONDS
                    )
                    .addTag(WORK_NAME)
                    .build()
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                // Best-effort: a scheduling failure must never break the caller (the queue stays
                // persisted and will be scheduled on the next app start / enqueue).
                Log.w(TAG, "Scheduling the offline queue drain failed: ${e.message}")
            }
        }

        /**
         * True when the active network reports [NetworkCapabilities.NET_CAPABILITY_INTERNET].
         * When the system service is unavailable we return true and let WorkManager's own
         * `NetworkType.CONNECTED` constraint govern.
         */
        fun isOnline(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
