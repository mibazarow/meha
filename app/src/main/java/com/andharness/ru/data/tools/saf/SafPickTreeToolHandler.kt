package com.andharness.ru.data.tools.saf

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.domain.ui.requestBoundedUi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `saf_pick_tree`: asks the UI to launch `ACTION_OPEN_DOCUMENT_TREE`, verifies the picked tree is
 * readable and persists the resulting grant through [SafGrantRepository].
 *
 * Persistable URI permissions (`takePersistableUriPermission`) are intentionally NOT taken here: the
 * UI (`ChatScreen`) takes them before it submits [UiResult.UriResult].
 * https://developer.android.com/training/data-storage/shared/documents-files
 */
internal class SafPickTreeToolHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
    private val safGrantRepository: SafGrantRepository,
) : ToolHandler {

    override val name = "saf_pick_tree"

    override suspend fun execute(arguments: JsonObject): ToolResult {
        return try {
            val title = (arguments["title"] as? JsonPrimitive)?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val res = uiBridge.requestBoundedUi(UiRequest.OpenDocumentTree(title))

            // Only a UriResult carrying a non-null uri is a successful pick; every other
            // outcome is reported honestly instead of being treated as a granted folder.
            val uri = when (res) {
                is UiResult.UriResult -> res.uri
                    ?: return ToolResult(false, "Папка не выбрана.")
                UiResult.Cancelled -> return ToolResult(false, "Папка не выбрана.")
                is UiResult.Failed -> return ToolResult(
                    false,
                    "Не удалось открыть диалог выбора папки: ${res.message}"
                )
                is UiResult.ConsentResult -> return ToolResult(
                    false,
                    "Неожиданный ответ диалога выбора папки."
                )
                is UiResult.ActivityResult -> return ToolResult(
                    false,
                    "Неожиданный ответ диалога выбора папки."
                )
                UiResult.PrintDialogShown -> return ToolResult(
                    false,
                    "Неожиданный ответ диалога выбора папки."
                )
            }

            // DocumentsContract.getTreeDocumentId — https://developer.android.com/reference/android/provider/DocumentsContract#getTreeDocumentId(android.net.Uri)
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            // DocumentFile.fromTreeUri — https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile#fromTreeUri(android.content.Context,%20android.net.Uri)
            val tree = DocumentFile.fromTreeUri(context, uri)
            val name = tree?.name ?: uri.lastPathSegment ?: uri.toString()

            // Verify the grant actually works before persisting it: a tree URI that cannot be
            // read is never stored as a usable grant.
            if (tree?.canRead() != true) {
                return ToolResult(false, "Нет доступа к выбранной папке.")
            }

            val id = safGrantRepository.add(uri.toString(), treeDocId, name)
            ToolResult(
                true,
                "Доступ к папке сохранён: id=$id, имя=\"$name\", uri=$uri"
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
