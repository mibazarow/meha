package com.andharness.ru.data.embedding

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
import com.andharness.ru.domain.downloads.DownloadResume
import com.andharness.ru.domain.embedding.EmbedderModelSpec
import com.andharness.ru.domain.embedding.EmbedderSourceSelector
import com.andharness.ru.domain.formatting.ByteFormat
import com.andharness.ru.domain.localserver.FreeSpaceDecision
import com.andharness.ru.domain.localserver.ModelDownloadState
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
 * Downloads the EmbeddingGemma 2 embedder artifacts ([EmbedderModelSpec.FILES]: weights + mmproj)
 * once, resumably, as a single long-running foreground worker. Mirrors `ModelDownloadWorker` but is
 * fully decoupled from it (its own store, work name and notification id), so the chat/local LLM
 * download is never affected.
 *
 * Why a foreground worker: the two files total ~860 MiB, so a plain background worker risks being
 * killed mid-transfer. [setForeground] runs it under WorkManager's `SystemForegroundService` with an
 * ongoing, cancelable notification.
 * References:
 * - Long-running workers / setForeground / CoroutineWorker —
 *   https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
 *   https://developer.android.com/reference/androidx/work/CoroutineWorker
 * - Foreground service type `dataSync` (permission already declared) —
 *   https://developer.android.com/develop/background-work/services/fgs/service-types
 * - Streaming download + `Range` request via OkHttp —
 *   https://square.github.io/okhttp/recipes/
 * - `MessageDigest` SHA-256 for post-download verification —
 *   https://developer.android.com/reference/java/security/MessageDigest
 *
 * Dependencies come from the Hilt graph through an [EntryPoint] (the project's established worker
 * pattern), so no custom WorkerFactory is needed:
 * https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
class EmbedderDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            EmbedderDownloadEntryPoint::class.java,
        )
        val store = entry.embedderDownloadStore()
        val notifier = entry.notificationHelper()
        // Redirects MUST be followed: the primary server serves the file directly, but the Hugging
        // Face fallback answers `302` and redirects to its CDN (`*.cdn.hf.co`). OkHttp follows
        // redirects by default (`followRedirects` / `followSslRedirects` both default to `true`), but
        // they are pinned here so a future change to the shared client cannot silently break the
        // fallback. `HttpURLConnection` has the same documented default — it "will follow up to five
        // HTTP redirects ... from one origin server to another", with `setInstanceFollowRedirects`
        // defaulting to `true`.
        // https://square.github.io/okhttp/recipes/
        // https://developer.android.com/reference/java/net/HttpURLConnection
        val client = entry.okHttpClient().newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val files = EmbedderModelSpec.FILES
        val total = EmbedderModelSpec.TOTAL_BYTES
        files.forEach { store.targetFile(it).parentFile?.mkdirs() }

        // Everything already verified on disk: nothing to do.
        if (store.isReady()) {
            store.setState(ModelDownloadState.Done(store.targetFile().absolutePath))
            return Result.success()
        }

        // ---- Free-space policy: never start a download that cannot fit ----
        val existing = files.sumOf { file ->
            store.partialFile(file).takeIf { it.isFile }?.length()?.coerceAtMost(file.sizeBytes) ?: 0L
        }
        val remaining = (total - existing).coerceAtLeast(0L)
        val free = freeBytes()
        val decision = EmbedderModelSpec.freeSpaceDecision(free, remaining)
        if (decision == FreeSpaceDecision.INSUFFICIENT) {
            val reason = EmbedderModelSpec.freeSpaceMessage(decision, free, remaining)
            Log.w(TAG, "Embedder download not started: $reason")
            store.setFailed(reason)
            return Result.failure(workDataOf(KEY_REASON to reason))
        }

        // ---- Foreground: keep the transfer alive ----
        runCatching { notifier.ensureChannels() }
        try {
            setForeground(foregroundInfo(notifier, existing, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A foreground service can be refused (e.g. started while the app is backgrounded on
            // Android 12+). Log honestly and keep going: WorkManager may still run the worker.
            Log.w(TAG, "Could not enter foreground; continuing as a normal worker", e)
        }

        var completed = 0L
        for (file in files) {
            val target = store.targetFile(file)
            val partial = store.partialFile(file)
            if (target.isFile && target.length() == file.sizeBytes) {
                completed += file.sizeBytes
                continue
            }
            // A stale, non-matching final file must not shadow a fresh download.
            if (target.isFile) target.delete()

            when (val outcome = downloadFile(file, completed, total, store, notifier, client)) {
                FileOutcome.Success -> completed += file.sizeBytes
                is FileOutcome.Retry -> return Result.retry()
                is FileOutcome.Failure -> return Result.failure(workDataOf(KEY_REASON to outcome.reason))
            }
        }

        if (!store.isReady()) {
            // Defence in depth: reachable only if a file vanished between the loop and here.
            store.setFailed("загружены не все файлы модели")
            return Result.retry()
        }
        Log.i(TAG, "Embedder download complete: ${store.targetFile().absolutePath}")
        store.setState(ModelDownloadState.Done(store.targetFile().absolutePath))
        return Result.success()
    }

    /**
     * Downloads one [file] from the first working source, reporting combined progress, then verifies
     * and finalises it. The PRIMARY source ([EmbedderModelSpec.ModelFile.url], our server) is tried
     * first; on any failure the Hugging Face fallback is tried next ([EmbedderSourceSelector]). The
     * resumable `.part` is shared across sources, so a fallback attempt resumes rather than restarting,
     * and whichever source delivers the bytes is verified against the single expected SHA-256 of the
     * file. [base] is the number of bytes already completed by earlier files in the batch.
     */
    private suspend fun downloadFile(
        file: EmbedderModelSpec.ModelFile,
        base: Long,
        total: Long,
        store: EmbedderDownloadStore,
        notifier: NotificationHelper,
        client: OkHttpClient,
    ): FileOutcome {
        val target = store.targetFile(file)
        val partial = store.partialFile(file)

        // A `.part` that already reached full size just needs verifying/finalising (e.g. the process
        // died between the last byte and the rename).
        if (partial.isFile && partial.length() == file.sizeBytes) {
            return finalizeFile(store, file, partial, target)
        }

        val sources = EmbedderSourceSelector.sources(file)
        Log.i(TAG, "Downloading ${file.fileName} from ${sources.size} source(s)")
        val outcome = EmbedderSourceSelector.tryInOrder(sources) { sourceUrl ->
            attemptFromSource(file, sourceUrl, base, total, store, notifier, client).toSelectorOutcome()
        }
        return when (outcome) {
            EmbedderSourceSelector.Outcome.Success -> FileOutcome.Success
            is EmbedderSourceSelector.Outcome.Retryable -> FileOutcome.Retry(outcome.reason)
            is EmbedderSourceSelector.Outcome.Permanent -> FileOutcome.Failure(outcome.reason)
        }
    }

    /**
     * Performs one resumable transfer of [file] from [sourceUrl], reporting progress and returning a
     * [FileOutcome] that also encodes whether a failure was transient (retry) or permanent. Ordinary
     * network/HTTP failures never throw here — the verdict is returned so the caller can fall back to
     * the next source.
     */
    private suspend fun attemptFromSource(
        file: EmbedderModelSpec.ModelFile,
        sourceUrl: String,
        base: Long,
        total: Long,
        store: EmbedderDownloadStore,
        notifier: NotificationHelper,
        client: OkHttpClient,
    ): FileOutcome {
        val target = store.targetFile(file)
        val partial = store.partialFile(file)

        var downloaded = if (partial.isFile) partial.length() else 0L
        var response: Response? = null
        try {
            val request = Request.Builder()
                .url(sourceUrl)
                .apply { DownloadResume.rangeHeader(downloaded)?.let { header("Range", it) } }
                .build()

            val resp = client.newCall(request).execute()
            response = resp
            if (!resp.isSuccessful) return httpFailure(resp.code, file, partial, store)

            // A 206 means the range was honoured (append); anything else restarts from zero.
            val appending = downloaded > 0L && resp.code == 206
            val body = resp.body
            if (body == null) {
                val reason = "сервер вернул пустой ответ"
                store.setFailed(reason)
                return FileOutcome.Retry(reason)
            }
            downloaded = if (appending) downloaded else 0L
            val serverTotal = DownloadResume.resolveTotal(
                contentRange = resp.header("Content-Range"),
                offset = downloaded,
                contentLength = body.contentLength(),
                fallback = file.sizeBytes,
            )
            if (serverTotal != file.sizeBytes) {
                // The remote object changed size; the final size/SHA-256 check will reject it.
                Log.w(TAG, "Server reports $serverTotal bytes for ${file.fileName}, expected ${file.sizeBytes}")
            }

            if (!appending && downloaded > 0L) {
                // Server ignored our Range and is resending the whole file.
                Log.i(TAG, "Server ignored Range; restarting ${file.fileName} from zero")
            }

            var lastReported = downloaded
            reportProgress(store, notifier, base + downloaded, total)
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
                            reportProgress(store, notifier, base + downloaded, total)
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (e: CancellationException) {
            // Keep the `.part` so the next run resumes; be honest about the stop.
            store.setFailed("отменено")
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Embedder download I/O error; keeping partial for resume", e)
            val reason = e.message ?: "сетевая ошибка"
            store.setFailed(reason)
            return FileOutcome.Retry(reason)
        } catch (e: Exception) {
            Log.e(TAG, "Embedder download failed", e)
            val reason = e.message ?: "неизвестная ошибка"
            store.setFailed(reason)
            return FileOutcome.Failure(reason)
        } finally {
            response?.close()
        }

        if (downloaded < file.sizeBytes) {
            // Short read: not an error worth discarding progress — resume next time.
            val reason = "загрузка прервана на " +
                "${EmbedderModelSpec.progressPercent(downloaded, file.sizeBytes)}%"
            store.setFailed(reason)
            return FileOutcome.Retry(reason)
        }
        return finalizeFile(store, file, partial, target)
    }

    /** Verifies the completed `.part` (size + SHA-256) and atomically moves it into place. */
    private fun finalizeFile(
        store: EmbedderDownloadStore,
        file: EmbedderModelSpec.ModelFile,
        partial: File,
        target: File,
    ): FileOutcome {
        if (partial.length() != file.sizeBytes) {
            val reason = "размер загруженного файла не совпадает с ожидаемым"
            store.setFailed(reason)
            return FileOutcome.Retry(reason)
        }
        store.setState(ModelDownloadState.Verifying)
        val actual = sha256(partial)
        if (!actual.equals(file.sha256, ignoreCase = true)) {
            // A corrupt partial can only be re-fetched, not resumed; report a permanent failure.
            partial.delete()
            val reason = "контрольная сумма модели не совпала"
            store.setFailed(reason)
            return FileOutcome.Failure(reason)
        }
        if (!partial.renameTo(target)) {
            val reason = "не удалось переименовать файл модели"
            store.setFailed(reason)
            return FileOutcome.Failure(reason)
        }
        return FileOutcome.Success
    }

    /** Maps a non-2xx response to an honest [FileOutcome]: transient codes retry, others permanent. */
    private fun httpFailure(
        code: Int,
        file: EmbedderModelSpec.ModelFile,
        partial: File,
        store: EmbedderDownloadStore,
    ): FileOutcome {
        if (code == 416) {
            // Range not satisfiable: the partial is ahead of/corrupt vs the server object.
            partial.delete()
            val reason = "частичный файл повреждён — загрузка начнётся заново"
            store.setFailed(reason)
            return FileOutcome.Retry(reason)
        }
        val reason = "сервер ответил $code"
        store.setFailed(reason)
        Log.w(TAG, "Embedder download HTTP failure (${file.fileName}): $reason")
        return if (code == 408 || code == 429 || code in 500..599) {
            FileOutcome.Retry(reason)
        } else {
            FileOutcome.Failure(reason)
        }
    }

    private suspend fun reportProgress(
        store: EmbedderDownloadStore,
        notifier: NotificationHelper,
        downloaded: Long,
        total: Long,
    ) {
        store.setDownloading(downloaded, total)
        runCatching { setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to total)) }
        runCatching { setForeground(foregroundInfo(notifier, downloaded, total)) }
    }

    private fun foregroundInfo(
        notifier: NotificationHelper,
        downloaded: Long,
        total: Long,
    ): ForegroundInfo {
        val notification = buildNotification(notifier, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.NOTIFICATION_ID_EMBEDDER_DOWNLOAD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NotificationHelper.NOTIFICATION_ID_EMBEDDER_DOWNLOAD, notification)
        }
    }

    private fun buildNotification(
        notifier: NotificationHelper,
        downloaded: Long,
        total: Long,
    ): Notification {
        val percent = EmbedderModelSpec.progressPercent(downloaded, total)
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val builder = NotificationCompat.Builder(applicationContext, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Загрузка модели семантического поиска")
            .setContentText(
                "$percent% · ${ByteFormat.humanBytes(downloaded)} из ${ByteFormat.humanBytes(total)}"
            )
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "Отмена", cancelIntent)
        runCatching {
            builder.setContentIntent(
                notifier.contentIntent(null, null, NotificationHelper.NOTIFICATION_ID_EMBEDDER_DOWNLOAD)
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

    /** Per-file outcome of the download loop; a non-success carries the reason already stored. */
    private sealed interface FileOutcome {
        data object Success : FileOutcome
        data class Retry(val reason: String) : FileOutcome
        data class Failure(val reason: String) : FileOutcome
    }

    /** Maps a per-source [FileOutcome] to the source selector's retry/permanent verdict. */
    private fun FileOutcome.toSelectorOutcome(): EmbedderSourceSelector.Outcome = when (this) {
        FileOutcome.Success -> EmbedderSourceSelector.Outcome.Success
        is FileOutcome.Retry -> EmbedderSourceSelector.Outcome.Retryable(reason)
        is FileOutcome.Failure -> EmbedderSourceSelector.Outcome.Permanent(reason)
    }

    companion object {
        const val WORK_NAME = "andharness_embedder_download"

        /** WorkManager progress keys, for the (separate) UI wiring task. */
        const val KEY_DOWNLOADED = "embedder_downloaded_bytes"
        const val KEY_TOTAL = "embedder_total_bytes"
        const val KEY_REASON = "embedder_download_reason"

        private const val TAG = "Andharness"
        private const val BUFFER_BYTES = 64 * 1024
        private const val REPORT_STEP_BYTES = 1024L * 1024L

        /**
         * Enqueues the single unique download. Called automatically from
         * `AndharnessApplication.onCreate`, so the ~860 MiB of embedder artifacts are fetched in the
         * background without any user action; there is no UI toggle by design.
         *
         * `UNMETERED` prefers Wi-Fi/unmetered networks so the large transfer never runs on the user's
         * metered mobile data; when no unmetered network is available the work simply stays queued
         * (graceful offline). `ExistingWorkPolicy.KEEP` means an in-flight/completed download is not
         * restarted — enqueuing on every app start is therefore idempotent and cheap.
         * `NetworkType.UNMETERED` / `Constraints.Builder` and `ExistingWorkPolicy.KEEP`:
         * https://developer.android.com/reference/androidx/work/Constraints.Builder
         * https://developer.android.com/reference/androidx/work/ExistingWorkPolicy
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbedderDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Hilt bridge so [EmbedderDownloadWorker] needs no custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EmbedderDownloadEntryPoint {
    fun embedderDownloadStore(): EmbedderDownloadStore
    fun notificationHelper(): NotificationHelper
    fun okHttpClient(): OkHttpClient
}
