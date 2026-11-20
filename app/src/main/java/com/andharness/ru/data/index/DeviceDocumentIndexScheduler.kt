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
 * Arms the device-document indexer. Cadence is HARDCODED (no user setting): a periodic incremental
 * pass every [INTERVAL_MINUTES] minutes and a one-shot FULL pass once (first run / embedder change),
 * plus an [enqueueIncrementalNow] entry point callers may use to request a prompt pass.
 *
 * All requests require `battery not low` so a large scan never runs on a dying battery. Network is not
 * required: the embedder is fully on-device. Idempotent — safe to call from `Application.onCreate`.
 * The document pass shares the process-wide [DeviceIndexLock] with the image indexer, so even if both
 * fire at once they run one after the other.
 *
 * References:
 * - Unique periodic/one-time work + constraints —
 *   https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work
 * - `ExistingPeriodicWorkPolicy.KEEP` / `ExistingWorkPolicy.KEEP` —
 *   https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy
 *   https://developer.android.com/reference/androidx/work/ExistingWorkPolicy
 */
@Singleton
class DeviceDocumentIndexScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Arms the periodic incremental pass and the one-shot full pass (both KEEP, so idempotent). */
    fun start() {
        try {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val periodic = PeriodicWorkRequestBuilder<DeviceDocumentIndexWorker>(
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

            val full = OneTimeWorkRequestBuilder<DeviceDocumentIndexWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(DeviceDocumentIndexWorker.KEY_MODE to DeviceDocumentIndexWorker.MODE_FULL)
                )
                .build()
            manager.enqueueUniqueWork(WORK_FULL, ExistingWorkPolicy.KEEP, full)
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling device document indexing failed", e)
        }
    }

    /**
     * Enqueues a prompt incremental pass (e.g. a realtime observer). KEEP coalesces a burst into at
     * most one queued pass; the periodic pass covers anything missed.
     */
    fun enqueueIncrementalNow() {
        try {
            val request = OneTimeWorkRequestBuilder<DeviceDocumentIndexWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                )
                .setInputData(
                    workDataOf(
                        DeviceDocumentIndexWorker.KEY_MODE to DeviceDocumentIndexWorker.MODE_INCREMENTAL
                    )
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_INCREMENTAL, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.e(TAG, "Enqueuing a realtime document index pass failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"

        const val WORK_PERIODIC = "device_document_index_periodic"
        const val WORK_FULL = "device_document_index_full"
        const val WORK_INCREMENTAL = "device_document_index_incremental"

        /** Incremental cadence: 45 minutes (within the requested 30–60 min band). */
        const val INTERVAL_MINUTES = 45L

        /** Small first-fire delay, staggered after the image pass so startup stays light. */
        const val INITIAL_DELAY_MINUTES = 3L
    }
}
