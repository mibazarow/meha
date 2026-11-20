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

/** `media_trash`: moves media files to the system trash through the consent dialog (API 30+). */
internal class MediaTrashToolHandler(
    private val context: Context,
    private val io: MediaPathIo,
    private val consent: MediaConsentRequester,
) : ToolHandler {
    override val name = "media_trash"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["paths"] as? JsonPrimitive)?.content.orEmpty()
            val resolved = io.resolveMediaPaths(raw)
            resolved.error?.let { return@withContext ToolResult(false, "$it Перемещение в Корзину отменено.") }
            val files = resolved.files

            // MediaStore.createTrashRequest requires API 30 (Android 11+): below that there
            // is no system trash-consent dialog. Reference:
            // https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E,%20boolean)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext ToolResult(
                    false,
                    "Перемещение медиа в системную Корзину требует Android 11 (API 30) или новее. " +
                        "На этой версии удаляйте файлы напрямую (files_delete) при доступе ко всем файлам.",
                )
            }

            val (uris, skipped) = io.mediaUrisFor(files)
            if (uris.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Ни один файл не найден в медиатеке: ${skipped.joinToString(", ")}.",
                )
            }

            // The move to trash only happens after the user confirms in the system dialog,
            // which the Activity launches from our IntentSender.
            // https://developer.android.com/reference/android/content/IntentSender
            val trashRequest = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            val consentResult = consent.request(
                trashRequest.intentSender,
                "trash",
                timeoutMs = MEDIA_TRASH_TIMEOUT_MS,
            )
            consent.errorMessage(
                consentResult,
                declined = "Пользователь отклонил перемещение в Корзину.",
                cancelled = "Перемещение в Корзину отменено: диалог закрыт.",
            )?.let { return@withContext ToolResult(false, it) }

            // POST-VERIFY: each item is trashed when IS_TRASHED reports 1 for its row.
            // Reference: MediaStore.MediaColumns.IS_TRASHED —
            // https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_TRASHED
            val projection = arrayOf(MediaStore.MediaColumns.IS_TRASHED)
            var trashed = 0
            for (uri in uris) {
                val isTrashed = try {
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        c.moveToFirst() &&
                            c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)) == 1
                    } ?: false
                } catch (e: Exception) {
                    Log.e("MediaToolsHandler", "media_trash verify failed for $uri", e)
                    false
                }
                if (isTrashed) trashed++
            }

            val remaining = uris.size - trashed
            val report = StringBuilder("Перемещено в Корзину $trashed из ${uris.size}.")
            if (skipped.isNotEmpty()) {
                report.append(" Пропущены (нет в медиатеке): ${skipped.joinToString(", ")}.")
            }
            if (remaining > 0) {
                report.append(" Не удалось переместить: $remaining.")
            }
            ToolResult(trashed > 0, report.toString())
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_trash failed", e)
            ToolResult(false, "Не удалось переместить медиафайлы в Корзину: ${e.message}")
        }
    }

    private companion object {
        /** `media_trash` maximum time to wait for the user to confirm the trash request. */
        private const val MEDIA_TRASH_TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS
    }
}
