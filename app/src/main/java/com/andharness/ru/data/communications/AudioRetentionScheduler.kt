package com.andharness.ru.data.communications

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.AudioRetentionGcWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the daily, power-guarded audio retention GC ([AudioRetentionGcWorker]). The worker reads the
 * current `keepAudioDays` / `audioCacheLimitMb` from [CommunicationsConfigStore] on every run, so
 * no re-arm is needed when the user edits them; the work itself is deferred under critical power.
 *
 * Idempotent — safe to call from `Application.onCreate`.
 * Reference: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 */
@Singleton
class AudioRetentionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun start() {
        try {
            val request = PeriodicWorkRequestBuilder<AudioRetentionGcWorker>(
                GC_INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling the audio retention GC failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"

        const val WORK_NAME = "andharness_audio_retention"

        private const val GC_INTERVAL_HOURS = 24L
    }
}
