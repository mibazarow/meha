package com.andharness.ru.data.messages

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.bg.StoredMessageEmbeddingEntryPoint
import kotlinx.coroutines.flow.firstOrNull
import dagger.hilt.android.EntryPointAccessors

/**
 * Bounded, power-guarded background embedder for captured messages. Each run embeds up to
 * [BATCH_SIZE] `stored_messages` rows that still have no vector, so full SMS / captured messenger
 * text becomes semantically searchable without blocking ingest or the UI. Everything is best-effort:
 * any failure is logged and the run ends with `success` (the next periodic run retries) rather than a
 * tight retry loop.
 *
 * Skips entirely — without loading the on-device model or scanning messages — when
 * `semanticSearchEnabled` is off; [StoredMessageVectorRepository.embedMissing] checks the same flag
 * again as defence in depth. Dependencies come from the Hilt graph via an [StoredMessageEmbeddingEntryPoint],
 * so no custom WorkerFactory is needed (same pattern as
 * [com.andharness.ru.bg.MemoryEmbeddingWorker]).
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 * Reference: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started
 */
class StoredMessageEmbeddingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            StoredMessageEmbeddingEntryPoint::class.java,
        )
        val enabled = entry.llmSettingsStore().config.firstOrNull()?.semanticSearchEnabled == true
        if (!enabled) return Result.success()
        val deferReason = entry.powerGuard().deferReason()
        if (deferReason != null) {
            Log.i(TAG, "Message embedding deferred by the power guard: $deferReason")
            return Result.success()
        }
        return try {
            val stored = entry.storedMessageVectorRepository().embedMissing(BATCH_SIZE)
            if (stored > 0) Log.i(TAG, "Message embedding stored $stored vector(s)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Message embedding failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Upper bound on the messages embedded per run, so one run stays cheap. */
        const val BATCH_SIZE = 64
    }
}
