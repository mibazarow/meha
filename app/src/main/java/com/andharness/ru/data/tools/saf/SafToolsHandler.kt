package com.andharness.ru.data.tools.saf

import android.content.Context
import com.andharness.ru.domain.saf.SafGrantRepository
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge

/**
 * Builds [ToolHandler]s for the Storage Access Framework tools. [saf_pick_tree] asks the UI to
 * launch `ACTION_OPEN_DOCUMENT_TREE`, verifies the picked tree is readable and persists the
 * resulting grant through [SafGrantRepository]. This class is a thin coordinator that wires one
 * [ToolHandler] per tool, in their original registration order.
 *
 * References:
 * - `DocumentFile.fromTreeUri(Context, Uri)` — builds a `DocumentFile` view of a tree URI:
 *   https://developer.android.com/reference/androidx/documentfile/provider/DocumentFile#fromTreeUri(android.content.Context,%20android.net.Uri)
 * - `androidx.documentfile:documentfile:1.0.1` —
 *   https://developer.android.com/jetpack/androidx/releases/documentfile
 * - `DocumentsContract.getTreeDocumentId(Uri)` — extracts the tree document id from a tree URI
 *   returned by `ACTION_OPEN_DOCUMENT_TREE`:
 *   https://developer.android.com/reference/android/provider/DocumentsContract#getTreeDocumentId(android.net.Uri)
 * - Persistable URI permissions (`takePersistableUriPermission`) are intentionally NOT taken
 *   here: the UI (`ChatScreen`) takes them before it submits `UiResult.UriResult`. See
 *   https://developer.android.com/training/data-storage/shared/documents-files
 */
class SafToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
    private val safGrantRepository: SafGrantRepository,
) {

    fun handlers(): List<ToolHandler> {
        val access = SafDocumentAccess(context, safGrantRepository)
        return listOf(
            SafPickTreeToolHandler(context, uiBridge, safGrantRepository),
            SafGrantsListToolHandler(safGrantRepository, access),
            SafGrantRemoveToolHandler(context, safGrantRepository),
            SafGrantTestToolHandler(safGrantRepository, access),
            SafListToolHandler(access),
            SafReadToolHandler(context, access),
            SafWriteToolHandler(context, access),
            SafMkdirToolHandler(access),
            SafDeleteToolHandler(access),
            SafCreateDocumentToolHandler(context, uiBridge),
            SafOpenDocumentToolHandler(context, uiBridge),
        )
    }
}
