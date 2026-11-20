package com.andharness.ru.data.tools.index

import com.andharness.ru.data.index.DeviceIndexKind
import com.andharness.ru.data.index.DeviceSearchResult
import com.andharness.ru.data.tools.media.MediaActionPlanner
import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Pure rendering of `device_search` hits into agent-friendly, actionable lines. Each line carries
 * the `content://` URI plainly (copy-pasteable into `media_open` / `media_share`), the absolute file
 * path when it could be derived, and — for images — the MediaStore id. Android-free, so it is
 * unit-testable on the JVM.
 */
internal object DeviceSearchFormatter {

    /** Max characters of a matched document snippet shown in one result line. */
    const val SNIPPET_CHARS = 200

    /**
     * One result line: `- [фото] Имя — URI: <uri> (путь: <path>) (id <n>)`. [absolutePath] and the
     * MediaStore id are optional and omitted when unavailable.
     */
    fun formatLine(result: DeviceSearchResult, absolutePath: String? = null): String = buildString {
        append("- ")
        append(if (result.kind == DeviceIndexKind.IMAGE) "[фото]" else "[документ]")
        append(' ')
        append(result.displayName)
        if (result.kind == DeviceIndexKind.DOC_CHUNK) {
            val snippet = collapse(result.snippet)
            if (snippet.isNotEmpty()) {
                append(": ")
                append(snippet)
            }
        }
        append(" — URI: ")
        append(result.sourceUri)
        absolutePath?.takeIf { it.isNotBlank() }?.let {
            append(" (путь: ")
            append(it)
            append(')')
        }
        if (result.kind == DeviceIndexKind.IMAGE) {
            MediaActionPlanner.mediaStoreId(result.sourceUri)?.let {
                append(" (id ")
                append(it)
                append(')')
            }
        }
    }

    /** Footer telling the agent how to act on a hit; appended once after the result list. */
    fun footer(): String =
        "Чтобы открыть найденное — вызови media_open с его uri; чтобы поделиться — media_share."

    private fun collapse(text: String): String {
        val flattened = collapseWhitespace(text)
        if (flattened.length <= SNIPPET_CHARS) return flattened
        return flattened.take(SNIPPET_CHARS - 1).trimEnd() + '…'
    }
}
