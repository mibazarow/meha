package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.retrieval.MemoryGc
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * Periodic, power-guarded GC for stale low-value long-term memories: prunes
 * only `importance == 0` memories untouched for more than [MemoryGc.STALE_AFTER_MS] (180 days),
 * through the same `kind = "memory"`-scoped [AssistRepository.deleteMemory] path `memory_delete`
 * uses, so importance-1/2 memories and ordinary notes are never removed.
 *
 * Dependencies are pulled from the Hilt graph via an [EntryPoint] so the worker needs no custom
 * WorkerFactory — same pattern as [DeviceFactsSweepWorker].
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class MemoryGcWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            MemoryGcEntryPoint::class.java
        )
        // Power guard (update.md idea #19): GC is non-essential periodic maintenance, so it is
        // skipped under critical power. Success (not retry) is honest: the next periodic run will
        // pick the work up once the device recovers.
        val deferReason = entry.powerGuard().deferReason()
        if (deferReason != null) {
            Log.i(TAG, "Memory GC deferred by the power guard: $deferReason")
            return Result.success()
        }
        val repository = entry.assistRepository()
        val now = System.currentTimeMillis()
        return try {
            val memories = repository.listMemoriesForGc(SCAN_LIMIT)
            val stale = memories
                .filter { MemoryGc.shouldGc(it.importance, it.lastUsedAt, it.createdAt, now) }
                .take(GC_BATCH_SIZE)
            var deleted = 0
            for (memory in stale) if (repository.deleteMemory(memory.id)) deleted++
            Log.i(TAG, "Memory GC pruned $deleted stale importance-0 memory row(s) (scanned ${memories.size})")
            Result.success()
        } catch (e: Exception) {
            // Reported honestly and not retried in a loop: the next periodic run will try again.
            Log.e(TAG, "Memory GC failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Upper bound on the rows inspected per run, so one run stays cheap. */
        const val SCAN_LIMIT = 500

        /** Upper bound on the rows removed per run. */
        const val GC_BATCH_SIZE = 20
    }
}
