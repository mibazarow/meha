package com.andharness.ru.data.tools.media

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registers files that an in-process tool wrote with plain file I/O in the system **MediaStore**, so
 * they show up in the device Gallery / other media apps without a manual re-scan.
 *
 * This is the reusable counterpart of the [MediaToolsHandler] `media_scan` tool (which stays the
 * user-facing tool); the camera tools call [register] right after a capture produced its output.
 *
 * - [MediaScannerConnection.scanFile] is asynchronous: the framework inserts/updates the row in
 *   MediaStore and invokes [MediaScannerConnection.OnScanCompletedListener] on a binder thread, once
 *   per requested path, with `null` as the `Uri` when the file could not be scanned.
 * - The listener is bridged into coroutines with [CompletableDeferred] and awaited under
 *   [withTimeoutOrNull], so no thread is ever blocked.
 * - Registration **never throws** and never turns a successful capture into a failure: any problem
 *   (no callback, timeout, exception) is logged and reported honestly through [ScanOutcome], which
 *   the caller appends to its success message.
 *
 * `scanFile` does not move or rename the file — the destination stays exactly where the tool wrote
 * it (`DCIM/Meha/…` by default).
 *
 * References:
 * - `MediaScannerConnection.scanFile(Context, String[], String[], OnScanCompletedListener)` and
 *   `OnScanCompletedListener.onScanCompleted(String, Uri)` —
 *   https://developer.android.com/reference/android/media/MediaScannerConnection#scanFile(android.content.Context,%20java.lang.String%5B%5D,%20java.lang.String%5B%5D,%20android.media.MediaScannerConnection.OnScanCompletedListener)
 * - Scanned files become visible in the Gallery through MediaStore —
 *   https://developer.android.com/training/data-storage/shared/media
 */
object MediaScanRegistration {

    /** Maximum time to wait for the MediaStore scan callback (mirrors MediaToolsHandler). */
    const val SCAN_TIMEOUT_MS = 10_000L

    private const val TAG = "MediaScanRegistration"

    /**
     * Result of one [register] call. [scannedUris] holds only the `Uri`s MediaStore actually
     * returned, so [registered] is a positive confirmation rather than an assumption.
     */
    data class ScanOutcome(
        val requestedPaths: List<String>,
        val scannedUris: List<Uri>,
        val timedOut: Boolean,
    ) {

        /** `true` only when MediaStore returned at least one `Uri` for the scanned file(s). */
        val registered: Boolean get() = scannedUris.isNotEmpty()

        /** Honest, human-readable sentence to append to the tool's success message. */
        val galleryNote: String
            get() = when {
                !registered && timedOut ->
                    "Добавление в галерею не подтверждено (таймаут MediaStore ${SCAN_TIMEOUT_MS} мс)."
                !registered ->
                    "В галерею файл не добавлен (MediaStore не вернул Uri)."
                scannedUris.size < requestedPaths.size ->
                    "В галерею добавлено ${scannedUris.size} из ${requestedPaths.size} файлов."
                requestedPaths.size == 1 ->
                    "Файл добавлен в галерею."
                else ->
                    "Все ${requestedPaths.size} файлов добавлены в галерею."
            }
    }

    /** Registers one produced [file]; see [register] for the list overload. */
    suspend fun register(context: Context, file: File): ScanOutcome = register(context, listOf(file))

    /**
     * Registers every existing, non-empty file of [files] in MediaStore with a **single**
     * `scanFile` call and waits (bounded by [SCAN_TIMEOUT_MS]) for the per-path callbacks.
     * Returns the [ScanOutcome] — it never throws and never fails the caller.
     */
    suspend fun register(context: Context, files: List<File>): ScanOutcome {
        val present = files.filter { it.isFile && it.length() > 0L }
        if (present.isEmpty()) {
            return ScanOutcome(files.map { it.absolutePath }, emptyList(), timedOut = false)
        }

        val paths = present.map { it.absolutePath }
        return try {
            // One callback per path arrives on a binder thread; the deferred completes on the last
            // one (complete() is idempotent and safe from any thread), so the scan is awaited
            // without blocking the camera worker thread.
            val remaining = AtomicInteger(paths.size)
            val uris = ConcurrentLinkedQueue<Uri>()
            val finished = CompletableDeferred<Unit>()
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, uri ->
                if (uri != null) {
                    uris += uri
                }
                if (remaining.decrementAndGet() <= 0) {
                    finished.complete(Unit)
                }
            }

            val outcome = ScanOutcome(
                requestedPaths = paths,
                scannedUris = uris.toList(),
                timedOut = withTimeoutOrNull(SCAN_TIMEOUT_MS) { finished.await() } == null,
            )
            when {
                outcome.timedOut -> Log.w(
                    TAG,
                    "MediaStore scan timed out after ${SCAN_TIMEOUT_MS} ms for ${paths.size} file(s): " +
                        paths.joinToString(", "),
                )
                !outcome.registered -> Log.w(
                    TAG,
                    "MediaStore returned no Uri for ${paths.size} file(s): ${paths.joinToString(", ")}",
                )
                else -> Log.d(
                    TAG,
                    "Registered ${outcome.scannedUris.size}/${paths.size} file(s) in MediaStore: " +
                        paths.joinToString(", "),
                )
            }
            outcome
        } catch (e: Exception) {
            // A scan must never break a capture that already succeeded on disk.
            Log.w(TAG, "MediaStore registration failed for ${paths.size} file(s)", e)
            ScanOutcome(paths, emptyList(), timedOut = false)
        }
    }
}
