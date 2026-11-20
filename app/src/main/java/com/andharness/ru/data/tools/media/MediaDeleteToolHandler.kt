package com.andharness.ru.data.tools.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiConsentPolicy
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** `media_delete`: deletes media files through the system delete-consent dialog (API 30+). */
internal class MediaDeleteToolHandler(
    private val context: Context,
    private val io: MediaPathIo,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {
    override val name = "media_delete"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val raw = (arguments["paths"] as? JsonPrimitive)?.content.orEmpty()
            val entries = raw.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
            if (entries.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Укажите paths — пути к медиафайлам через запятую.",
                )
            }

            // Resolve every entry against the tool root up front: any invalid/missing path
            // aborts the whole request so nothing is deleted for a partly wrong input.
            val bad = ArrayList<String>()
            val files = ArrayList<File>()
            for (entry in entries) {
                val file = try {
                    io.resolveMediaPath(entry)
                } catch (e: IllegalArgumentException) {
                    bad.add("«$entry» (${e.message})")
                    continue
                }
                if (!file.exists() || !file.isFile) {
                    bad.add("«$entry»")
                    continue
                }
                files.add(file)
            }
            if (bad.isNotEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Файлы не найдены или некорректны: ${bad.joinToString(", ")}. Удаление отменено.",
                )
            }

            // MediaStore.createDeleteRequest requires API 30 (Android 11+): below that there
            // is no system delete-consent dialog. Reference:
            // https://developer.android.com/reference/android/provider/MediaStore#createDeleteRequest(android.content.ContentResolver,%20java.util.Collection%3Candroid.net.Uri%3E)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext ToolResult(
                    false,
                    "Удаление медиа с системным подтверждением требует Android 11 (API 30) или новее. " +
                        "На этой версии удаляйте файлы напрямую (files_delete) при доступе ко всем файлам.",
                )
            }

            // Map each file to its media collection Uri. `MediaStore.getMediaUri(Context, Uri)`
            // converts a DocumentsProvider document Uri (not a File), so the lookup is done
            // through `MediaStore.Files` by absolute path (`_data`) and mapped by `media_type`.
            // A null result means the file is not indexed as media in the library.
            val uris = ArrayList<Uri>()
            val skipped = ArrayList<String>()
            for (file in files) {
                val uri = io.mediaStoreMediaUri(file)
                if (uri == null) skipped.add("«${file.name}»") else uris.add(uri)
            }
            if (uris.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "Ни один файл не найден в медиатеке: ${skipped.joinToString(", ")}.",
                )
            }

            // The deletion only happens after the user confirms in the system dialog, which
            // the Activity launches from our IntentSender.
            // https://developer.android.com/reference/android/content/IntentSender
            val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, uris)
            val consent = uiBridge.requestBoundedUi(
                UiRequest.MediaConsent(deleteRequest.intentSender, "delete"),
                timeoutMs = MEDIA_DELETE_TIMEOUT_MS,
            )
            when (consent) {
                is UiResult.ConsentResult -> if (!consent.granted) {
                    return@withContext ToolResult(false, "Пользователь отклонил удаление.")
                }
                UiResult.Cancelled ->
                    return@withContext ToolResult(false, "Удаление отменено: диалог закрыт.")
                is UiResult.Failed ->
                    return@withContext ToolResult(false, "Не удалось получить подтверждение: ${consent.message}")
                is UiResult.UriResult ->
                    return@withContext ToolResult(false, "Неожиданный ответ диалога подтверждения.")
                is UiResult.ActivityResult ->
                    return@withContext ToolResult(false, "Неожиданный ответ диалога подтверждения.")
                UiResult.PrintDialogShown ->
                    return@withContext ToolResult(false, "Неожиданный ответ диалога подтверждения.")
            }

            // POST-VERIFY: a media row is gone when the resolver returns an empty cursor.
            var deleted = 0
            for (uri in uris) {
                val gone = try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { it.count == 0 } ?: true
                } catch (e: Exception) {
                    Log.e("MediaToolsHandler", "media_delete verify failed for $uri", e)
                    false
                }
                if (gone) deleted++
            }

            val remaining = uris.size - deleted
            val report = StringBuilder("Удалено $deleted из ${uris.size}.")
            if (skipped.isNotEmpty()) {
                report.append(" Пропущены (нет в медиатеке): ${skipped.joinToString(", ")}.")
            }
            if (remaining > 0) {
                report.append(" Не удалось удалить: $remaining.")
            }
            ToolResult(deleted > 0, report.toString())
        } catch (e: Exception) {
            Log.e("MediaToolsHandler", "media_delete failed", e)
            ToolResult(false, "Не удалось удалить медиафайлы: ${e.message}")
        }
    }

    private companion object {
        /** `media_delete` maximum time to wait for the user to confirm in the system dialog. */
        private const val MEDIA_DELETE_TIMEOUT_MS = UiConsentPolicy.TIMEOUT_MS
    }
}
