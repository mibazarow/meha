package com.andharness.ru.bg

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.communications.CommunicationsConfigStore
import com.andharness.ru.domain.communications.CommunicationProcessDecision
import com.andharness.ru.domain.communications.CommunicationProcessGate
import com.andharness.ru.domain.communications.speech.SpeechModel
import dagger.hilt.android.EntryPointAccessors
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Periodic, power-guarded background processing of call recordings: scans the configured folders
 * (when any are set), then transcribes a bounded number of `PENDING` recordings.
 *
 * Every run is a no-op unless the user's [CommunicationsMode] and the device power state allow it,
 * and the total time spent transcribing per calendar day is bounded by
 * [CommunicationsConfigStore.currentProcessBudgetRemainingMs] (`maxProcessMinutesPerDay`). The gate
 * is the pure [CommunicationProcessGate], so the whole decision table is unit-tested on the JVM.
 *
 * Honesty rules:
 * - `OFF` and an exhausted daily budget are `SKIP` (the next day resets the budget);
 * - `CHARGING` while not charging, or a power-guard deferral, is `DEFER` — a no-op, never an error;
 * - a missing speech model is reported and skipped, so pending rows are NOT marked `FAILED` just
 *   because the user has not downloaded a model;
 * - a per-recording failure is logged and does not abort the remaining recordings;
 * - the function never throws; only a transient [IOException] asks WorkManager to retry.
 *
 * The worker needs no custom WorkerFactory because dependencies come through
 * [CommunicationsAutomationEntryPoint] (the documented Hilt pattern):
 * https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 * WorkManager references:
 * - `CoroutineWorker`: https://developer.android.com/reference/androidx/work/CoroutineWorker
 * - long-running workers: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
 */
class CommunicationsAutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                CommunicationsAutomationEntryPoint::class.java,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable; automation skipped", e)
            return Result.success()
        }

        val config = entry.communicationsConfigStore()
        val decision = CommunicationProcessGate.decide(
            mode = config.currentMode(),
            snapshot = entry.powerGuard().snapshot(),
            budgetRemainingMs = config.currentProcessBudgetRemainingMs(),
        )
        when (decision) {
            CommunicationProcessDecision.SKIP -> {
                Log.i(TAG, "communications automation skipped (mode=${config.currentMode()}, budget left ${config.currentProcessBudgetRemainingMs()} ms)")
                return Result.success()
            }
            CommunicationProcessDecision.DEFER -> {
                Log.i(TAG, "communications automation deferred (mode=${config.currentMode()})")
                return Result.success()
            }
            CommunicationProcessDecision.RUN -> Unit
        }

        return try {
            if (config.currentRecordingsFolders().isNotEmpty()) {
                entry.recordingsScanner().scan(SCAN_LIMIT).fold(
                    onSuccess = { result ->
                        Log.i(
                            TAG,
                            "recordings scan: found=${result.found} added=${result.added} skipped=${result.skipped}",
                        )
                    },
                    onFailure = { error ->
                        Log.w(TAG, "recordings scan failed: ${error.message}")
                    },
                )
            }
            processPending(entry, config)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Transient disk/provider IO: let WorkManager retry with its back-off.
            Log.e(TAG, "communications automation hit transient IO; retrying", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "communications automation failed", e)
            Result.success()
        }
    }

    /**
     * Transcribes up to [MAX_PENDING_PER_RUN] oldest pending recordings, re-checking the mode, the
     * power gate and the daily budget before each one so a long run stops as soon as any condition
     * degrades. The real elapsed time of each transcription is charged to the daily budget.
     */
    private suspend fun processPending(
        entry: CommunicationsAutomationEntryPoint,
        config: CommunicationsConfigStore,
    ) {
        val model = SpeechModel.fromId(config.currentWhisperModelId()) ?: SpeechModel.DEFAULT
        if (!entry.speechModelStore().isDownloaded(model)) {
            Log.i(TAG, "transcription skipped: model ${model.id} is not downloaded")
            return
        }

        val pending = entry.recordingsRepository().listPending(MAX_PENDING_PER_RUN)
        if (pending.isEmpty()) {
            Log.i(TAG, "no pending recordings to transcribe")
            return
        }

        val language = config.currentWhisperLanguage()
        var done = 0
        var failed = 0
        for (recording in pending) {
            val remaining = config.currentProcessBudgetRemainingMs()
            if (remaining <= 0L) {
                Log.i(TAG, "daily processing budget exhausted; stopping")
                break
            }
            val decision = CommunicationProcessGate.decide(
                mode = config.currentMode(),
                snapshot = entry.powerGuard().snapshot(),
                budgetRemainingMs = remaining,
            )
            if (decision != CommunicationProcessDecision.RUN) {
                Log.i(TAG, "stopping: gate is $decision (budget left $remaining ms)")
                break
            }

            val startedAt = System.currentTimeMillis()
            val result = entry.transcriptionService()
                .transcribeRecording(recording.id, model, language)
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            config.addProcessBudgetUsedMs(elapsed)

            result.fold(
                onSuccess = { outcome ->
                    done++
                    Log.i(TAG, "transcribed recording ${recording.id}: ${outcome.segmentCount} segment(s), ${elapsed} ms")
                },
                onFailure = { error ->
                    failed++
                    Log.w(TAG, "transcription of recording ${recording.id} failed: ${error.message}")
                },
            )
        }
        Log.i(TAG, "processing run finished: done=$done failed=$failed, budget left ${config.currentProcessBudgetRemainingMs()} ms")
    }

    companion object {
        private const val TAG = "Andharness"

        /** Upper bound on MediaStore rows examined per scan. */
        const val SCAN_LIMIT = 200

        /** Upper bound on recordings transcribed per run, so one tick stays bounded. */
        const val MAX_PENDING_PER_RUN = 5
    }
}
