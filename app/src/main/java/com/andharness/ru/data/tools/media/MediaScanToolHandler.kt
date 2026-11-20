package com.andharness.ru.data.tools.media

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** `media_scan`: registers a file in the MediaStore library. */
internal class MediaScanToolHandler(
    private val context: Context,
    private val io: MediaPathIo,
) : ToolHandler {
    override val name = "media_scan"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = io.pathFile(arguments, "path")
            if (!file.exists()) {
                return@withContext ToolResult(false, "Файл не найден: «${file.name}».")
            }
            if (!file.isFile) {
                return@withContext ToolResult(false, "Не файл: «${file.name}».")
            }

            // scanFile(Context, String[], String[], OnScanCompletedListener) inserts/updates
            // the file in MediaStore and invokes the listener on a binder thread; the callback
            // is awaited via CompletableDeferred (withTimeoutOrNull), never blocking a thread.
            // Reference: MediaScannerConnection.scanFile —
            // https://developer.android.com/reference/android/media/MediaScannerConnection#scanFile(android.content.Context,%20java.lang.String%5B%5D,%20java.lang.String%5B%5D,%20android.media.MediaScannerConnection.OnScanCompletedListener)
            val scanned = CompletableDeferred<Uri?>()
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
            ) { _, uri -> scanned.complete(uri) }

            val uri = withTimeoutOrNull(SCAN_TIMEOUT_MS) { scanned.await() }
            if (uri != null) {
                ToolResult(true, "Файл просканирован в медиатеку: $uri")
            } else {
                ToolResult(false, "Не удалось просканировать файл в медиатеку")
            }
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_scan failed", e)
            ToolResult(false, "Не удалось просканировать файл в медиатеку")
        }
    }

    private companion object {
        /** `media_scan` maximum time to wait for the MediaScannerConnection callback. */
        private const val SCAN_TIMEOUT_MS = 10_000L
    }
}
