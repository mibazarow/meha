package com.andharness.ru.data.tools.saf

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `saf_create_document`: creates a brand-new document through the system "Save as" dialog
 * (`Intent.ACTION_CREATE_DOCUMENT`). When `content` is supplied it is written immediately with
 * `ContentResolver.openOutputStream(Uri, "wt")` and the resulting length is verified.
 *
 * https://developer.android.com/reference/android/content/Intent#ACTION_CREATE_DOCUMENT
 */
internal class SafCreateDocumentToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {

    override val name = "saf_create_document"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val filename = (arguments["filename"] as? JsonPrimitive)?.content?.trim()
            if (filename.isNullOrEmpty()) {
                return@withContext ToolResult(false, "Параметр filename обязателен.")
            }
            val mime = (arguments["mime"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            val content = (arguments["content"] as? JsonPrimitive)?.content

            val res = uiBridge.requestBoundedUi(
                UiRequest.CreateDocument(suggestedName = filename, mimeType = mime)
            )

            // Only a UriResult carrying a non-null uri means the user actually saved a
            // document; every other outcome is reported honestly.
            val uri = when (res) {
                is UiResult.UriResult -> res.uri
                    ?: return@withContext ToolResult(false, "Документ не создан.")
                UiResult.Cancelled -> return@withContext ToolResult(false, "Документ не создан.")
                is UiResult.Failed -> return@withContext ToolResult(
                    false,
                    "Не удалось создать документ: ${res.message}"
                )
                is UiResult.ConsentResult -> return@withContext ToolResult(
                    false,
                    "Неожиданный ответ диалога создания документа."
                )
                is UiResult.ActivityResult -> return@withContext ToolResult(
                    false,
                    "Неожиданный ответ диалога создания документа."
                )
                UiResult.PrintDialogShown -> return@withContext ToolResult(
                    false,
                    "Неожиданный ответ диалога создания документа."
                )
            }

            if (content == null) {
                return@withContext ToolResult(true, "Документ создан: uri=$uri")
            }

            val bytes = content.toByteArray(Charsets.UTF_8)
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext ToolResult(
                    false,
                    "Не удалось открыть документ на запись: $uri"
                )
            out.use {
                it.write(bytes)
                it.flush()
            }

            // Post-write verification: the document must report exactly the UTF-8 byte count.
            val actual = DocumentFile.fromSingleUri(context, uri)?.length()
            if (actual == null || actual != bytes.size.toLong()) {
                return@withContext ToolResult(
                    false,
                    "Проверка после записи не пройдена: ожидалось ${bytes.size} байт, " +
                        "записано ${actual ?: "неизвестно"}."
                )
            }
            ToolResult(true, "Документ создан и записан (${bytes.size} байт): uri=$uri")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
