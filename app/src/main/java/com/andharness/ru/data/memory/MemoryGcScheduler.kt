package com.andharness.ru.data.memory

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.MemoryGcWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the daily, power-guarded memory GC ([MemoryGcWorker]). The work itself
 * is skipped under critical power by the guard inside the worker.
 *
 * Idempotent — safe to call from `Application.onCreate`.
 * Reference: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 */
@Singleton
class MemoryGcScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun start() {
        try {
            val request = PeriodicWorkRequestBuilder<MemoryGcWorker>(
                GC_INTERVAL_HOURS, TimeUnit.HOURS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                GC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling memory GC failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"
        const val GC_WORK_NAME = "memory_gc"
        private const val GC_INTERVAL_HOURS = 24L
    }
}
