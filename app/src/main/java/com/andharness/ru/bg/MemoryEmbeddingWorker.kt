package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.memory.MemoryVectorRepository
import kotlinx.coroutines.flow.firstOrNull
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Bounded, power-guarded background embedder for long-term memory. Each run embeds
 * up to [BATCH_SIZE] memories that are missing a vector or whose vector is stale for the currently
 * configured embedding model; everything is best-effort, so any failure is logged and the run ends
 * with `success` (the next periodic run retries) rather than a retry loop.
 *
 * Skips entirely — without loading the on-device model or scanning memory — when the
 * `semanticSearchEnabled` switch is off. [MemoryVectorRepository.embedMissing] checks the same flag
 * again as defence in depth.
 *
 * Dependencies are pulled from the Hilt graph via an [EntryPoint] so the worker needs no custom
 * WorkerFactory — same pattern as [MemoryGcWorker].
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class MemoryEmbeddingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            MemoryEmbeddingEntryPoint::class.java
        )
        // Semantic search off: do nothing at all — the bundled embedder stays unloaded.
        // Success (not retry) is honest: enabling the switch later will be picked up by the next run.
        val enabled = entry.llmSettingsStore().config.firstOrNull()?.semanticSearchEnabled == true
        if (!enabled) return Result.success()
        // Power guard: embedding is optional, battery-hungry maintenance, so it is skipped under
        // critical power. Success (not retry) is honest — the next periodic run picks it up.
        val deferReason = entry.powerGuard().deferReason()
        if (deferReason != null) {
            Log.i(TAG, "Memory embedding deferred by the power guard: $deferReason")
            return Result.success()
        }
        return try {
            val stored = entry.memoryVectorRepository().embedMissing(BATCH_SIZE)
            if (stored > 0) {
                Log.i(TAG, "Memory embedding stored $stored vector(s)")
            }
            Result.success()
        } catch (e: Exception) {
            // Reported honestly and not retried in a tight loop: the next periodic run will try again.
            Log.e(TAG, "Memory embedding failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Upper bound on the memories embedded per run, so one run stays cheap. */
        const val BATCH_SIZE = 50
    }
}
