package com.andharness.ru.data.messages

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the periodic, power-guarded message embedder ([StoredMessageEmbeddingWorker]). The work itself
 * is skipped under critical power and is a no-op when semantic search is off.
 *
 * Idempotent — safe to call from `Application.onCreate`. [triggerNow] enqueues a single bounded
 * catch-up run (e.g. after a batch of messages was ingested) without disturbing the periodic schedule.
 *
 * Reference: https://developer.android.com/training/background-work/background-tasks/persistent/getting-started
 * Reference: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 */
@Singleton
class StoredMessageEmbeddingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun start() {
        try {
            val request = PeriodicWorkRequestBuilder<StoredMessageEmbeddingWorker>(
                EMBED_INTERVAL_HOURS, TimeUnit.HOURS
            )
                // Populate vectors shortly after app start, then every 6 hours; without this the first
                // run would wait a full interval. The worker stays power-guarded and is a no-op when
                // semantic search is off.
                .setInitialDelay(0, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                EMBED_WORK_NAME,
                // UPDATE re-applies the current constraint/cadence on upgrade without losing the work.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling message embedding failed", e)
        }
    }

    /** Enqueues one bounded catch-up run; a newer call replaces a pending one. Best-effort. */
    fun triggerNow() {
        try {
            val request = OneTimeWorkRequestBuilder<StoredMessageEmbeddingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Triggering message embedding failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"
        const val EMBED_WORK_NAME = "stored_message_embedding"
        const val ONE_SHOT_WORK_NAME = "stored_message_embedding_now"

        /**
         * Every 6 hours: new messages become semantically searchable well within a day without
         * hammering the CPU. Each run is capped by [StoredMessageEmbeddingWorker.BATCH_SIZE].
         */
        private const val EMBED_INTERVAL_HOURS = 6L
    }
}
