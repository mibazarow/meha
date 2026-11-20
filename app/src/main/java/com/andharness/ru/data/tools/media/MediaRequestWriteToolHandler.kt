package com.andharness.ru.data.tools.media

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiConsentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `media_request_write`: requests write access to media files through the consent dialog. */
internal class MediaRequestWriteToolHandler(
    private val context: Context,
    private val io: MediaPathIo,
    private val consent: MediaConsentRequester,
) : ToolHandler {
    override val name = "media_request_write"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["paths"] as? JsonPrimitive)?.content.orEmpty()
            val resolved = io.resolveMediaPaths(raw)
            resolved.error?.let { return@withContext ToolResult(false, it) }
            val files = resolved.files

            // MediaStore.createWriteRequest requires API 30 (Android 11+): below that there
            // is no system write-consent dialog. Reference:
            // https://developer.android.com/reference/android/provider/MediaStore#createWriteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext ToolResult(
                    false,
                    "Запрос права на изменение чужих медиафайлов требует Android 11 (API 30) или новее.",
                )
            }

            val (uris, skipped) = io.mediaUrisFor(files)
            if (uris.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Ни один файл не найден в медиатеке: ${skipped.joinToString(", ")}.",
                )
            }

            // Write access is only granted after the user confirms in the system dialog,
            // which the Activity launches from our IntentSender.
            // https://developer.android.com/reference/android/content/IntentSender
            val writeRequest = MediaStore.createWriteRequest(context.contentResolver, uris)
            val consentResult = consent.request(
                writeRequest.intentSender,
                "write",
                timeoutMs = MEDIA_WRITE_TIMEOUT_MS,
            )
            consent.errorMessage(
                consentResult,
                declined = "Пользователь отклонил запрос права на запись.",
                cancelled = "Запрос права на запись отменён: диалог закрыт.",
            )?.let { return@withContext ToolResult(false, it) }

            // POST-VERIFY: write access is proven by opening each item read-write ("rw",
            // never "w", so the content is not truncated). A non-null descriptor means the
            // resolver granted write access. Reference:
            // https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,%20java.lang.String)
            var writable = 0
            for (uri in uris) {
                val ok = try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
                } catch (e: Exception) {
                    Log.e("MediaToolsHandler", "media_request_write verify failed for $uri", e)
                    false
                }
                if (ok) writable++
            }

            val remaining = uris.size - writable
            val report = StringBuilder("Доступ на запись получен для $writable из ${uris.size}.")
            if (skipped.isNotEmpty()) {
                report.append(" Пропущены (нет в медиатеке): ${skipped.joinToString(", ")}.")
            }
            if (remaining > 0) {
                report.append(" Без доступа на запись: $remaining.")
            }
            ToolResult(writable > 0, report.toString())
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_request_write failed", e)
            ToolResult(false, "Не удалось запросить право на изменение медиафайлов: ${e.message}")
        }
    }

    private companion object {
        /**
         * `media_request_write` maximum time to wait for the user to grant write access. Bounded by
         * [UiConsentPolicy.TIMEOUT_MS] so the wait fits inside the broadcast ANR window (BUG-014).
         */
        private const val MEDIA_WRITE_TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS
    }
}
