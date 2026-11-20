package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.objective.ObjectiveRunCoordinator
import dagger.hilt.android.EntryPointAccessors


/**
 * Periodic WorkManager trigger that advances queued/ACTIVE objectives in the background (checklist §2
 * «Параллельные задачи/фоновые цели»). Each run is one bounded [ObjectiveRunCoordinator.tick], which:
 * is power-guarded, respects the concurrency limit, and only ADMITS work — the actual agent turns run
 * on the coordinator's own scope (and are bounded by the objective run budget / step budget).
 *
 * Dependencies come from the Hilt graph via an [EntryPoint] so the worker needs no custom
 * WorkerFactory — the same pattern as [MemoryGcWorker]/[DeviceFactsSweepWorker].
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 *
 * The work never requires network (the LLM endpoint may be local), and the scheduler adds
 * `setRequiresBatteryNotLow(true)` where sensible; the coordinator additionally downgrades
 * concurrency to 1 (or defers entirely) via [com.andharness.ru.data.diagnostics.PowerGuard].
 */
class ObjectiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            ObjectiveWorkerEntryPoint::class.java
        )
        return try {
            val status = entry.objectiveCoordinator().tick()
            Log.i(
                TAG,
                "objective tick: running=${status.running.size} queued=${status.queued.size} " +
                    "limit=${status.effectiveLimit} note=${status.note}"
            )
            Result.success()
        } catch (e: Exception) {
            // Reported honestly and not retried in a tight loop: the next periodic run tries again.
            Log.e(TAG, "objective background tick failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "Andharness"
    }
}
