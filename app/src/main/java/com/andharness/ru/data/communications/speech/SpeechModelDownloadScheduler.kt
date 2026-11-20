package com.andharness.ru.data.communications.speech

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.andharness.ru.domain.communications.speech.SpeechModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues / cancels speech-model downloads ([SpeechModel]) as unique one-time work, mirroring
 * `com.andharness.ru.data.localserver.ModelDownloadWorker.enqueue`.
 *
 * Each model has its own unique work name ([SpeechModelDownloadWorker.workName]), so different models
 * can download concurrently while a repeated request for the SAME model keeps the in-flight one
 * (`ExistingWorkPolicy.KEEP`). Callers must cancel an in-flight download before deleting its files,
 * so the worker cannot recreate the `.part` it is writing to.
 *
 * References:
 * - `WorkManager.enqueueUniqueWork` / `ExistingWorkPolicy.KEEP` —
 *   https://developer.android.com/reference/androidx/work/WorkManager
 *   https://developer.android.com/reference/androidx/work/ExistingWorkPolicy
 */
@Singleton
class SpeechModelDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Enqueues the download of [model]; a no-op if that model is already downloading. */
    fun enqueue(model: SpeechModel) {
        try {
            SpeechModelDownloadWorker.enqueue(context, model)
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling the speech-model download failed", e)
        }
    }

    /** Cancels the in-flight download of [model], if any. Files are left intact for resume. */
    fun cancel(model: SpeechModel) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(SpeechModelDownloadWorker.workName(model))
        } catch (e: Exception) {
            Log.e(TAG, "Cancelling the speech-model download failed", e)
        }
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
