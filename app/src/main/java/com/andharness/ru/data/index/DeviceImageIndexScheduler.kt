package com.andharness.ru.data.index

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the device-image indexer. Cadence is HARDCODED (no user setting): a periodic incremental pass
 * every [INTERVAL_MINUTES] minutes and a one-shot FULL pass once (first run / embedder change), plus a
 * [enqueueIncrementalNow] entry point the realtime `ContentObserver` calls on gallery changes.
 *
 * All requests require `battery not low` so a large scan never runs on a dying battery. Network is not
 * required: the embedder is fully on-device. Idempotent — safe to call from `Application.onCreate`.
 *
 * References:
 * - Unique periodic/one-time work + constraints —
 *   https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work
 * - `ExistingPeriodicWorkPolicy.KEEP` / `ExistingWorkPolicy.KEEP` —
 *   https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy
 *   https://developer.android.com/reference/androidx/work/ExistingWorkPolicy
 */
@Singleton
class DeviceImageIndexScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Arms the periodic incremental pass and the one-shot full pass (both KEEP, so idempotent). */
    fun start() {
        try {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val periodic = PeriodicWorkRequestBuilder<DeviceImageIndexWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )

            val full = OneTimeWorkRequestBuilder<DeviceImageIndexWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(DeviceImageIndexWorker.KEY_MODE to DeviceImageIndexWorker.MODE_FULL))
                .build()
            manager.enqueueUniqueWork(WORK_FULL, ExistingWorkPolicy.KEEP, full)
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling device image indexing failed", e)
        }
    }

    /**
     * Enqueues a prompt incremental pass (called by the realtime `ContentObserver`). KEEP coalesces a
     * burst of gallery changes into at most one queued pass; the periodic pass covers anything missed.
     */
    fun enqueueIncrementalNow() {
        try {
            val request = OneTimeWorkRequestBuilder<DeviceImageIndexWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                )
                .setInputData(
                    workDataOf(DeviceImageIndexWorker.KEY_MODE to DeviceImageIndexWorker.MODE_INCREMENTAL)
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_INCREMENTAL, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.e(TAG, "Enqueuing a realtime image index pass failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"

        const val WORK_PERIODIC = "device_image_index_periodic"
        const val WORK_FULL = "device_image_index_full"
        const val WORK_INCREMENTAL = "device_image_index_incremental"

        /** Incremental cadence: 45 minutes (within the requested 30–60 min band). */
        const val INTERVAL_MINUTES = 45L

        /** Small first-fire delay so startup is never burdened by an immediate media scan. */
        const val INITIAL_DELAY_MINUTES = 2L
    }
}
