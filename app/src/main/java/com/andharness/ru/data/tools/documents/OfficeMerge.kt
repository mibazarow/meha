package com.andharness.ru.data.tools.documents

/**
 * Pure helpers for `office_merge` / `office_split`. Merging/splitting is only meaningful for some
 * document types, so the helpers stay deliberately small and the handlers state the exact semantics:
 * text documents are concatenated (or split on a separator line), tables are appended row by row,
 * and presentations are merged/split at the slide level by their content.
 */
internal object OfficeMerge {

    /** Separator inserted between merged text documents when the caller does not choose one. */
    const val DEFAULT_SEPARATOR = "\n\n"

    /** Concatenates text [parts] with [separator] between them (parts are kept verbatim). */
    fun mergeTexts(parts: List<String>, separator: String): String = parts.joinToString(separator)

    /**
     * Appends table [sources] in order. When [markers] matches the source count, a one-cell marker row
     * is inserted before each source after the first, so the merged table shows where a file starts.
     */
    fun mergeRows(
        sources: List<List<List<String>>>,
        markers: List<String?>? = null,
    ): List<List<String>> {
        val out = mutableListOf<List<String>>()
        sources.forEachIndexed { index, rows ->
            val marker = markers?.getOrNull(index)
            if (index > 0 && marker != null) out += listOf(marker)
            out += rows
        }
        return out
    }

    /**
     * Splits [text] on lines that equal [separator] (trimmed), dropping empty parts. An empty
     * [separator] falls back to splitting on a line of `---`.
     */
    fun splitText(text: String, separator: String): List<String> {
        val wanted = separator.trim().ifEmpty { "---" }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (line in text.split('\n')) {
            if (line.trim() == wanted) {
                parts += current.toString().trim('\n')
                current.setLength(0)
            } else {
                current.append(line).append('\n')
            }
        }
        parts += current.toString().trim('\n')
        return parts.map { it.trimEnd() }.filter { it.isNotEmpty() }
    }
}
