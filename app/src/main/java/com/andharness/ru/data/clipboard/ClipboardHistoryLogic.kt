package com.andharness.ru.data.clipboard


/**
 * Pure "smart paste" helpers shared by the [ClipboardHistoryStore] tools:
 * - [preview] renders a bounded, single-line preview for the list;
 * - [select] resolves the entry the model asked for — by 1-based index (newest = 1), by exact id, or
 *   the newest entry when neither is given. Both are JVM-testable.
 */
object ClipboardHistoryLogic {

    /** Characters of text shown per list line. */
    const val PREVIEW_CHARS = 80

    /** Single-line, length-bounded preview of a stored item. */
    fun preview(text: String, maxChars: Int = PREVIEW_CHARS): String {
        val oneLine = text.replace('\r', ' ').replace('\n', ' ').trim()
        if (maxChars <= 0) return ""
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }

    /**
     * Resolves the requested entry. [entries] is oldest-first (as [ClipboardHistoryStore.all]
     * returns); the index is 1-based within the newest-first view shown by the list tool. Returns
     * null when the requested id/index does not exist — the caller is expected to report that
     * honestly rather than silently falling back.
     */
    fun select(
        entries: List<ClipboardEntry>,
        index: Int? = null,
        id: String? = null,
    ): ClipboardEntry? {
        val newestFirst = entries.asReversed()
        if (!id.isNullOrBlank()) return newestFirst.firstOrNull { it.id == id }
        if (index != null) return newestFirst.getOrNull(index - 1)
        return newestFirst.firstOrNull()
    }
}
