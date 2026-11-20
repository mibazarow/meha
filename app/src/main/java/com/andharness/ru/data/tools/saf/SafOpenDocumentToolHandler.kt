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
import java.io.BufferedInputStream

/**
 * `saf_open_document`: asks the user to pick an existing document
 * (`Intent.ACTION_OPEN_DOCUMENT`), then returns its metadata and, for small text documents
 * (≤ 64 KiB, no NUL byte in the first 8 KiB), its UTF-8 content.
 *
 * https://developer.android.com/reference/android/content/Intent#ACTION_OPEN_DOCUMENT
 * `DocumentFile.fromSingleUri(Context, Uri)`:
 * https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile#fromSingleUri(android.content.Context,%20android.net.Uri)
 */
internal class SafOpenDocumentToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) : ToolHandler {

    override val name = "saf_open_document"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val mime = (arguments["mime"] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }
                ?: "*/*"

            val res = uiBridge.requestBoundedUi(
                UiRequest.OpenDocument(mimeTypes = listOf(mime))
            )

            val uri = when (res) {
                is UiResult.UriResult -> res.uri
                    ?: return@withContext ToolResult(false, "Документ не выбран.")
                UiResult.Cancelled -> return@withContext ToolResult(false, "Документ не выбран.")
                is UiResult.Failed -> return@withContext ToolResult(
                    false,
                    "Не удалось открыть документ: ${res.message}"
                )
                is UiResult.ConsentResult -> return@withContext ToolResult(
                    false,
                    "Неожиданный ответ диалога выбора документа."
                )
                is UiResult.ActivityResult -> return@withContext ToolResult(
                    false,
                    "Неожиданный ответ диалога выбора документа."
                )
                UiResult.PrintDialogShown -> return@withContext ToolResult(
                    false,
                    "Неожиданный ответ диалога выбора документа."
                )
            }

            val doc = DocumentFile.fromSingleUri(context, uri)
            val name = doc?.name ?: uri.lastPathSegment ?: uri.toString()
            val size = doc?.length() ?: -1L
            val sizeText = if (size >= 0L) "$size байт" else "неизвестен"
            val header = "Документ: «$name», размер: $sizeText, uri=$uri"

            if (size > SafLimits.MAX_OPEN_BYTES) {
                return@withContext ToolResult(
                    true,
                    "$header\n(файл больше ${SafLimits.MAX_OPEN_BYTES / 1024} КиБ; содержимое не возвращается)"
                )
            }

            // ContentResolver.openInputStream —
            // https://developer.android.com/reference/android/content/ContentResolver#openInputStream(android.net.Uri)
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ToolResult(
                    false,
                    "$header\n(не удалось открыть документ на чтение)"
                )
            stream.use { raw ->
                val input = BufferedInputStream(raw)
                val buffer = ByteArray(SafLimits.MAX_OPEN_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                // Binary detection on the first up-to-8 KiB: a NUL byte marks a non-text file.
                val probe = minOf(filled, SafLimits.BINARY_PROBE_BYTES)
                if ((0 until probe).any { buffer[it] == 0.toByte() }) {
                    return@withContext ToolResult(
                        true,
                        "$header\n(бинарный файл; содержимое не возвращается)"
                    )
                }
                val text = String(buffer, 0, filled, Charsets.UTF_8)
                ToolResult(true, "$header\n$text")
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
