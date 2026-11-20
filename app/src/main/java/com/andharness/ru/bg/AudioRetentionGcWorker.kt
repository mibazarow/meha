package com.andharness.ru.bg

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.andharness.ru.data.communications.isContentUri
import com.andharness.ru.domain.communications.AudioRetentionCandidate
import com.andharness.ru.domain.communications.AudioRetentionPolicy
import com.andharness.ru.domain.communications.Recording
import com.andharness.ru.domain.communications.TranscriptStatus
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Daily, power-guarded garbage collection of stored call audio.
 *
 * Only the audio files are deleted — transcript segments and recording metadata stay intact. The
 * selection is the pure [AudioRetentionPolicy]: audio older than `keepAudioDays` is removed unless
 * the row is pinned by `keepFlag`, then the oldest survivors are evicted until the on-device audio
 * cache is under `audioCacheLimitMb`. Recordings that are still `PENDING` or `RUNNING` are never
 * candidate files: their audio must survive until a transcript exists.
 *
 * Honesty rules: a power-guard deferral and a provider error are logged and never crash; one bad
 * file never aborts the sweep. A deleted `content://` audio is reported by the provider's row count.
 *
 * References (developer.android.com):
 * - `ContentResolver.delete` — https://developer.android.com/reference/android/content/ContentResolver#delete(android.net.Uri,%20java.lang.String,%20java.lang.String%5B%5D)
 * - Hilt workers — https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class AudioRetentionGcWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                AudioRetentionEntryPoint::class.java,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable; audio retention skipped", e)
            return Result.success()
        }

        // Non-essential periodic maintenance: a critical-power/thermal deferral is an honest no-op.
        val deferReason = entry.powerGuard().deferReason()
        if (deferReason != null) {
            Log.i(TAG, "audio retention deferred by the power guard: $deferReason")
            return Result.success()
        }

        val config = entry.communicationsConfigStore()
        val now = System.currentTimeMillis()
        return try {
            val recordings = entry.recordingsRepository().list(GC_SCAN_LIMIT)
            val byId = recordings.associateBy { it.id }
            val candidates = recordings
                .filter { it.isRetentionEligible() }
                .map {
                    AudioRetentionCandidate(
                        id = it.id,
                        sizeBytes = it.sizeBytes,
                        createdAt = it.createdAt,
                        keepFlag = it.keepFlag,
                    )
                }

            val plan = AudioRetentionPolicy.plan(
                candidates = candidates,
                nowMillis = now,
                keepAudioDays = config.currentKeepAudioDays(),
                cacheLimitBytes = config.currentAudioCacheLimitMb().toLong() * BYTES_PER_MB,
            )

            var deleted = 0
            var freedBytes = 0L
            for (id in plan.deleteIds) {
                val recording = byId[id] ?: continue
                val freed = deleteAudio(recording)
                if (freed > 0L) {
                    deleted++
                    freedBytes += freed
                }
            }
            Log.i(
                TAG,
                "audio retention: deleted=$deleted/${plan.deleteIds.size} file(s), " +
                    "freed=$freedBytes bytes (expired=${plan.expiredIds.size}, overflow=${plan.overflowIds.size}, " +
                    "scanned=${recordings.size})",
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "audio retention failed", e)
            Result.success()
        }
    }

    /** Audio whose transcript does not exist yet must not be deleted. */
    private fun Recording.isRetentionEligible(): Boolean =
        transcriptStatus != TranscriptStatus.PENDING && transcriptStatus != TranscriptStatus.RUNNING

    /**
     * Deletes the recording's audio bytes best-effort: a `content://` source goes through the
     * provider (which reports the affected row count), an absolute path through [File.delete].
     * Returns the bytes freed (`0` when nothing was removed), never throws.
     */
    private fun deleteAudio(recording: Recording): Long {
        val size = recording.sizeBytes.coerceAtLeast(0L)
        return try {
            if (isContentUri(recording.filePath)) {
                val rows = applicationContext.contentResolver
                    .delete(Uri.parse(recording.filePath), null, null)
                if (rows > 0) size else 0L
            } else {
                val file = File(recording.filePath)
                if (file.isFile) {
                    val actual = file.length().coerceAtLeast(0L)
                    if (file.delete()) actual else 0L
                } else {
                    0L
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not delete audio for recording ${recording.id}: ${e.message}")
            0L
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Upper bound on recording rows inspected per run, so one sweep stays cheap. */
        const val GC_SCAN_LIMIT = 1000

        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
