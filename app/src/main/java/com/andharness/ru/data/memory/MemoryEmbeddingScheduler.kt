package com.andharness.ru.data.memory

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.MemoryEmbeddingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the periodic, power-guarded memory embedder ([MemoryEmbeddingWorker]). The work
 * itself is skipped under critical power and is a no-op when semantic search is off.
 *
 * Idempotent — safe to call from `Application.onCreate`.
 * Reference: https://developer.android.com/training/background-work/background-tasks/persistent/getting-started
 * Reference: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 */
@Singleton
class MemoryEmbeddingScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun start() {
        try {
            val request = PeriodicWorkRequestBuilder<MemoryEmbeddingWorker>(
                EMBED_INTERVAL_HOURS, TimeUnit.HOURS
            )
                // The first population of memory vectors should happen shortly after app start, then
                // every 6 hours; without this the first run would wait a full interval. The worker
                // stays power-guarded and is a no-op when semantic search is off.
                // Reference: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest.Builder
                .setInitialDelay(0, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                EMBED_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling memory embedding failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"
        const val EMBED_WORK_NAME = "memory_embedding"

        /**
         * Every 6 hours: new memories become semantically searchable well within a day without
         * hammering the embeddings endpoint. Each run is capped by [MemoryEmbeddingWorker.BATCH_SIZE].
         */
        private const val EMBED_INTERVAL_HOURS = 6L
    }
}
