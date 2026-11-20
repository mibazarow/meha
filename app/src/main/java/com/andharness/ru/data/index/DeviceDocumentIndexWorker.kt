package com.andharness.ru.data.index

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.andharness.ru.R
import com.andharness.ru.data.embedding.EmbedderDownloadStore
import com.andharness.ru.data.notifications.NotificationHelper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Runs one device-document indexing pass ([DeviceDocumentIndexer]) under WorkManager.
 *
 * - A FULL pass (first run / embedder change) enters foreground with an ongoing progress notification,
 *   so a long first scan is not killed and its progress is visible; an incremental pass stays a quiet
 *   background worker because it is normally short.
 * - When the embedder artifacts are not downloaded yet it returns [Result.retry] so the pass is retried
 *   later instead of failing; once the model arrives the next attempt indexes normally.
 * - Any unexpected failure is logged and retried; the worker never crashes the process.
 *
 * Dependencies come from the Hilt graph through a [DeviceDocumentIndexEntryPoint] (the project's
 * established worker pattern — see `DeviceImageIndexWorker` / `ModelDownloadWorker`), so no custom
 * WorkerFactory is needed:
 * https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 *
 * Foreground/notification references:
 * - Long-running workers + `setForeground` —
 *   https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
 * - `ForegroundInfo` + foreground service type `dataSync` (permission declared in the manifest) —
 *   https://developer.android.com/reference/androidx/work/ForegroundInfo
 */
class DeviceDocumentIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            DeviceDocumentIndexEntryPoint::class.java,
        )
        val indexer = entry.deviceDocumentIndexer()
        val notifier = entry.notificationHelper()
        val embedderStore = entry.embedderDownloadStore()

        if (!embedderStore.isReady()) {
            Log.i(TAG, "Embedder artifacts not ready; deferring device document indexing")
            return Result.retry()
        }

        val full = inputData.getString(KEY_MODE) == MODE_FULL
        if (full) {
            runCatching { notifier.ensureChannels() }
            runCatching { setForeground(foregroundInfo(notifier, 0, 0)) }
                .onFailure { Log.w(TAG, "Could not enter foreground; running as a normal worker", it) }
        }

        val result = runCatching {
            indexer.index { done, total ->
                runCatching { setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total)) }
                if (full) {
                    runCatching { setForeground(foregroundInfo(notifier, done, total)) }
                }
            }
        }.getOrElse {
            Log.e(TAG, "Device document indexing failed", it)
            return Result.retry()
        }

        Log.i(
            TAG,
            "Device document index pass complete: indexed=${result.indexed} updated=${result.updated} " +
                "deleted=${result.deleted} skipped=${result.skipped} failed=${result.failed} " +
                "unsupported=${result.unsupported} " +
                "docs=${result.documentsIndexed} ocr=${result.ocrDocuments} " +
                "modelReady=${result.modelReady} permissionDenied=${result.permissionDenied}",
        )
        return Result.success(
            workDataOf(
                KEY_INDEXED to result.indexed,
                KEY_UPDATED to result.updated,
                KEY_DELETED to result.deleted,
            )
        )
    }

    private fun foregroundInfo(
        notifier: NotificationHelper,
        done: Int,
        total: Int,
    ): ForegroundInfo {
        val notification = buildNotification(notifier, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.NOTIFICATION_ID_DEVICE_DOCUMENT_INDEX,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NotificationHelper.NOTIFICATION_ID_DEVICE_DOCUMENT_INDEX, notification)
        }
    }

    private fun buildNotification(
        notifier: NotificationHelper,
        done: Int,
        total: Int,
    ): Notification {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val hasTotal = total > 0
        val percent = if (hasTotal) (done * 100 / total).coerceIn(0, 100) else 0
        val builder = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Индексация документов")
            .setContentText(
                if (hasTotal) "$done из $total" else "Подготовка…"
            )
            .setProgress(100, percent, !hasTotal)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Отмена", cancelIntent)
        runCatching {
            builder.setContentIntent(
                notifier.contentIntent(
                    null,
                    null,
                    NotificationHelper.NOTIFICATION_ID_DEVICE_DOCUMENT_INDEX,
                )
            )
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "Andharness"

        /** Input-data key selecting [MODE_FULL] or [MODE_INCREMENTAL]; absent means incremental. */
        const val KEY_MODE = "device_document_index_mode"
        const val MODE_FULL = "full"
        const val MODE_INCREMENTAL = "incremental"

        /** Progress keys: documents processed so far / total to process this pass. */
        const val KEY_DONE = "device_document_index_done"
        const val KEY_TOTAL = "device_document_index_total"

        /** Output-data keys with the pass counters (for observability / tests). */
        const val KEY_INDEXED = "device_document_index_indexed"
        const val KEY_UPDATED = "device_document_index_updated"
        const val KEY_DELETED = "device_document_index_deleted"
    }
}

/** Hilt entry point exposing the worker's graph (no custom WorkerFactory needed). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceDocumentIndexEntryPoint {
    fun deviceDocumentIndexer(): DeviceDocumentIndexer
    fun notificationHelper(): NotificationHelper
    fun embedderDownloadStore(): EmbedderDownloadStore
}
