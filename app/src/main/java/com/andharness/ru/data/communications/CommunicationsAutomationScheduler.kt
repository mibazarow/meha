package com.andharness.ru.data.communications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.CommunicationsAutomationWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the periodic background Communications run ([CommunicationsAutomationWorker]): scan the
 * configured folders, then transcribe a bounded number of pending recordings under the
 * mode/power/daily-budget gate.
 *
 * Idempotent — safe to call from `Application.onCreate`. `ExistingPeriodicWorkPolicy.UPDATE`
 * re-arms the unique work (e.g. after a build change) while preserving the period. Network is
 * deliberately NOT required (the pipeline is fully on-device), and `setRequiresBatteryNotLow(true)`
 * keeps the tick from firing on a dying battery; the worker additionally defers via the power guard.
 *
 * Official references:
 * - `PeriodicWorkRequest` (interval ≥ 15 min): https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 * - `Constraints`: https://developer.android.com/reference/androidx/work/Constraints
 * - `ExistingPeriodicWorkPolicy`: https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy
 */
@Singleton
class CommunicationsAutomationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun start() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CommunicationsAutomationWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling the Communications automation failed", e)
        }
    }

    /** Re-reads the config; call after the user changes a Communications setting. */
    fun refresh() = start()

    companion object {
        private const val TAG = "Andharness"

        const val WORK_NAME = "andharness_communications_automation"

        /** Tick period; the worker itself is a bounded no-op when the gate says SKIP/DEFER. */
        const val INTERVAL_MINUTES = 30L
    }
}
