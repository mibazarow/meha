package com.andharness.ru.data.objective

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.ObjectiveWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the periodic background objective run ([ObjectiveWorker]). Idempotent — safe to call from
 * `Application.onCreate`.
 *
 * Constraints: network is deliberately NOT required (the LLM endpoint may be a local server reached
 * without connectivity), and `setRequiresBatteryNotLow(true)` keeps the tick from firing on a dying
 * battery (the coordinator additionally degrades/defer via the power guard).
 * References:
 * - https://developer.android.com/reference/androidx/work/Constraints
 * - https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 * - https://developer.android.com/reference/androidx/work/WorkManager#enqueueUniquePeriodicWork(java.lang.String,%20androidx.work.ExistingPeriodicWorkPolicy,%20androidx.work.PeriodicWorkRequest)
 */
@Singleton
class ObjectiveWorkersScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun start() {
        try {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<ObjectiveWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling background objectives failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"
        const val WORK_NAME = "objective_workers"

        /** WorkManager's minimum periodic interval is 15 minutes. */
        const val INTERVAL_MINUTES = 15L
    }
}
