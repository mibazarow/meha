package com.andharness.ru.data.communications.speech

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.andharness.ru.R
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.common.Hashing
import com.andharness.ru.domain.communications.speech.SpeechModel
import com.andharness.ru.domain.communications.speech.SpeechModelDownloadState
import com.andharness.ru.domain.downloads.DownloadResume
import com.andharness.ru.domain.localserver.FreeSpaceDecision
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Downloads one speech model ([SpeechModel]) once, resumably, as a long-running worker. The model is
 * selected by [KEY_MODEL_ID] in the input data, so one worker class serves whisper weights and VAD.
 *
 * Why a foreground worker: whisper small is ~465 MiB, so a plain background worker risks being
 * killed mid-transfer. [setForeground] makes WorkManager run it under its `SystemForegroundService`
 * with an ongoing, cancelable notification.
 * References:
 * - Long-running workers / setForeground / CoroutineWorker —
 *   https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
 *   https://developer.android.com/reference/androidx/work/CoroutineWorker
 * - Foreground service type `dataSync` (permission already declared) —
 *   https://developer.android.com/develop/background-work/services/fgs/service-types
 * - Streaming download + `Range` request via OkHttp (resumable partial download) —
 *   https://square.github.io/okhttp/recipes/
 * - `MessageDigest` SHA-256 `digest()`/`update()` for post-download verification —
 *   https://developer.android.com/reference/java/security/MessageDigest
 *
 * Behaviour mirrors `ModelDownloadWorker`:
 * - free space is checked BEFORE network I/O; an impossible download fails without leaving a file;
 * - HTTP Range resume (`Range: bytes=<partial length>-`) when a `.part` exists and the server answers
 *   206; a 200 means the server ignored the range and the download restarts cleanly;
 * - on success the SHA-256 is verified and `.part` is renamed atomically into place;
 * - on failure the `.part` is KEPT for the next attempt, and only transient errors return
 *   [Result.retry]; permanent ones (no space, 404, checksum mismatch) return [Result.failure];
 * - cancellation keeps the `.part` and records `Failed("отменено")`.
 *
 * Dependencies come from the Hilt graph through an [EntryPoint] (the project's established worker
 * pattern — see `ScheduleWorker`), so no custom WorkerFactory is needed:
 * https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class SpeechModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
        val model = modelId?.let { SpeechModel.fromId(it) }
        if (model == null) {
            val reason = "неизвестная модель: ${modelId ?: "<нет id>"}"
            Log.w(TAG, "Speech-model download not started: $reason")
            return Result.failure(workDataOf(KEY_REASON to reason))
        }

        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            SpeechModelDownloadEntryPoint::class.java,
        )
        val store = entry.speechModelStore()
        val notifier = entry.notificationHelper()
        val client = entry.okHttpClient()

        val target = store.targetFile(model)
        val partial = store.partFile(model)
        target.parentFile?.mkdirs()

        // Already fully downloaded (e.g. the rename succeeded on a previous run): nothing to do.
        if (target.isFile && target.length() == model.approxBytes) {
            store.setState(SpeechModelDownloadState.Done(model.id, target.absolutePath))
            return Result.success()
        }
        // A stale, non-matching final file must not shadow a fresh download.
        if (target.isFile) target.delete()

        // A `.part` that already reached full size just needs verifying/finalising (e.g. the process
        // died between the last byte and the rename).
        if (partial.isFile && partial.length() == model.approxBytes) {
            return finalize(store, model, partial, target)
        }

        // ---- Free-space policy: never start a download that cannot fit ----
        val existing = if (partial.isFile) partial.length() else 0L
        val remaining = (model.approxBytes - existing).coerceAtLeast(0L)
        val free = freeBytes()
        val decision = SpeechModel.freeSpaceDecision(free, remaining)
        if (decision == FreeSpaceDecision.INSUFFICIENT) {
            val reason = SpeechModel.freeSpaceMessage(decision, free, remaining)
            Log.w(TAG, "Speech-model download not started: $reason")
            store.setFailed(model, reason)
            return Result.failure(workDataOf(KEY_REASON to reason))
        }

        // ---- Foreground: keep the transfer alive ----
        runCatching { notifier.ensureChannels() }
        try {
            setForeground(foregroundInfo(notifier, model, existing, model.approxBytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A foreground service can be refused (e.g. started while the app is backgrounded on
            // Android 12+). Log honestly and keep going: WorkManager may still run the worker.
            Log.w(TAG, "Could not enter foreground; continuing as a normal worker", e)
        }

        var downloaded = existing
        val total = model.approxBytes
        var response: Response? = null
        try {
            val request = Request.Builder()
                .url(model.downloadUrl)
                .apply { DownloadResume.rangeHeader(existing)?.let { header("Range", it) } }
                .build()

            val resp = client.newCall(request).execute()
            response = resp
            if (!resp.isSuccessful) return httpFailure(resp.code, store, model, partial)

            // A 206 means the range was honoured (append); anything else restarts from zero.
            val appending = existing > 0L && resp.code == 206
            val body = resp.body
            if (body == null) {
                store.setFailed(model, "сервер вернул пустой ответ")
                return Result.retry()
            }
            downloaded = if (appending) existing else 0L
            val serverTotal = DownloadResume.resolveTotal(
                contentRange = resp.header("Content-Range"),
                offset = downloaded,
                contentLength = body.contentLength(),
                fallback = total,
            )
            if (serverTotal != total) {
                // The remote object changed size; the final size/SHA-256 check will reject it.
                Log.w(TAG, "Server reports $serverTotal bytes, expected $total")
            }

            if (!appending && existing > 0L) {
                // Server ignored our Range and is resending the whole file.
                Log.i(TAG, "Server ignored Range; restarting the download from zero")
            }

            var lastReported = downloaded
            reportProgress(store, notifier, model, downloaded, total)
            body.byteStream().use { input ->
                FileOutputStream(partial, appending).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported >= REPORT_STEP_BYTES) {
                            lastReported = downloaded
                            reportProgress(store, notifier, model, downloaded, total)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (e: CancellationException) {
            // Keep the `.part` so the next run resumes; be honest about the stop.
            store.setFailed(model, "отменено")
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Speech-model download I/O error; keeping partial for resume", e)
            store.setFailed(model, e.message ?: "сетевая ошибка")
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Speech-model download failed", e)
            store.setFailed(model, e.message ?: "неизвестная ошибка")
            return Result.failure(workDataOf(KEY_REASON to (e.message ?: "неизвестная ошибка")))
        } finally {
            response?.close()
        }

        if (downloaded < total) {
            // Short read: not an error worth discarding progress — resume next time.
            store.setFailed(
                model,
                "загрузка прервана на ${SpeechModel.progressPercent(downloaded, total)}%",
            )
            return Result.retry()
        }
        return finalize(store, model, partial, target)
    }

    /** Verifies the completed `.part` (size + SHA-256) and atomically moves it into place. */
    private suspend fun finalize(
        store: SpeechModelStore,
        model: SpeechModel,
        partial: File,
        target: File,
    ): Result {
        if (partial.length() != model.approxBytes) {
            store.setFailed(model, "размер загруженного файла не совпадает с ожидаемым")
            return Result.retry()
        }
        store.setState(SpeechModelDownloadState.Verifying(model.id))
        val actual = sha256(partial)
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            // A corrupt partial can only be re-fetched, not resumed; report a permanent failure.
            partial.delete()
            val reason = "контрольная сумма модели не совпала"
            store.setFailed(model, reason)
            return Result.failure(workDataOf(KEY_REASON to reason))
        }
        if (!partial.renameTo(target)) {
            val reason = "не удалось переименовать файл модели"
            store.setFailed(model, reason)
            return Result.failure(workDataOf(KEY_REASON to reason))
        }
        Log.i(TAG, "Speech-model download complete: ${target.absolutePath}")
        store.setState(SpeechModelDownloadState.Done(model.id, target.absolutePath))
        return Result.success()
    }

    /** Maps a non-2xx response to an honest [Result]: transient codes retry, others are permanent. */
    private fun httpFailure(
        code: Int,
        store: SpeechModelStore,
        model: SpeechModel,
        partial: File,
    ): Result {
        if (code == 416) {
            // Range not satisfiable: the partial is ahead of/corrupt vs the server object.
            partial.delete()
            store.setFailed(model, "частичный файл повреждён — загрузка начнётся заново")
            return Result.retry()
        }
        val reason = "сервер ответил $code"
        store.setFailed(model, reason)
        Log.w(TAG, "Speech-model download HTTP failure: $reason")
        return if (code == 408 || code == 429 || code in 500..599) {
            Result.retry()
        } else {
            Result.failure(workDataOf(KEY_REASON to reason))
        }
    }

    private suspend fun reportProgress(
        store: SpeechModelStore,
        notifier: NotificationHelper,
        model: SpeechModel,
        downloaded: Long,
        total: Long,
    ) {
        store.setDownloading(model, downloaded, total)
        runCatching { setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to total)) }
        runCatching { setForeground(foregroundInfo(notifier, model, downloaded, total)) }
    }

    private fun foregroundInfo(
        notifier: NotificationHelper,
        model: SpeechModel,
        downloaded: Long,
        total: Long,
    ): ForegroundInfo {
        val notification = buildNotification(notifier, model, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.NOTIFICATION_ID_SPEECH_MODEL_DOWNLOAD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(
                NotificationHelper.NOTIFICATION_ID_SPEECH_MODEL_DOWNLOAD,
                notification,
            )
        }
    }

    private fun buildNotification(
        notifier: NotificationHelper,
        model: SpeechModel,
        downloaded: Long,
        total: Long,
    ): Notification {
        val percent = SpeechModel.progressPercent(downloaded, total)
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Загрузка модели распознавания")
            .setContentText(
                "${model.displayName}: $percent% · ${SpeechModel.humanBytes(downloaded)} из " +
                    SpeechModel.humanBytes(total)
            )
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Отмена", cancelIntent)
        runCatching {
            builder.setContentIntent(
                notifier.contentIntent(
                    null,
                    null,
                    NotificationHelper.NOTIFICATION_ID_SPEECH_MODEL_DOWNLOAD,
                )
            )
        }
        return builder.build()
    }

    /** Available bytes on the filesystem that holds app-private files; `0` when it cannot be read. */
    private fun freeBytes(): Long = try {
        StatFs(applicationContext.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        // Cannot prove there is room -> treat as no room rather than risk a half download.
        Log.w(TAG, "StatFs failed; assuming no free space", e)
        0L
    }

    /** SHA-256 of [file]'s contents as lowercase hex. */
    private fun sha256(file: File): String = file.inputStream().use { Hashing.sha256Hex(it) }

    companion object {
        /** WorkManager input key: the [SpeechModel.id] to download. */
        const val KEY_MODEL_ID = "speech_model_id"

        /** WorkManager progress keys, for the (separate) UI wiring task. */
        const val KEY_DOWNLOADED = "speech_model_downloaded_bytes"
        const val KEY_TOTAL = "speech_model_total_bytes"
        const val KEY_REASON = "speech_model_download_reason"

        private const val TAG = "Andharness"
        private const val BUFFER_BYTES = 64 * 1024
        private const val REPORT_STEP_BYTES = 1024L * 1024L

        /** Stable unique work name for [model], e.g. `andharness_speech_model_whisper_base`. */
        fun workName(model: SpeechModel): String = "andharness_speech_model_${model.id}"

        /**
         * Enqueues the download of [model] under its own unique work name. [ExistingWorkPolicy.KEEP]
         * means an in-flight download of the same model is not restarted; the network constraint
         * matches the app's connected-only policy.
         */
        fun enqueue(context: Context, model: SpeechModel) {
            val request = OneTimeWorkRequestBuilder<SpeechModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(workDataOf(KEY_MODEL_ID to model.id))
                .addTag(workName(model))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Hilt bridge so [SpeechModelDownloadWorker] needs no custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpeechModelDownloadEntryPoint {
    fun speechModelStore(): SpeechModelStore
    fun notificationHelper(): NotificationHelper
    fun okHttpClient(): OkHttpClient
}
