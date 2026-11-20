package com.andharness.ru.domain.chat

/**
 * Pure-Kotlin snippet extraction for chat search results: a short, single-line window around the
 * first literal occurrence of the query, or — for a semantic-only match where the query text does
 * not appear — a sensible head excerpt. No Android dependency, so it is unit-testable on the JVM
 * (see `ChatSnippetExtractorTest`).
 *
 * Mirrors the `chat_search` tool snippet (`snippetAround` in `data/tools/misc/ChatRead.kt`) so the
 * tool and the UI show the same kind of excerpt.
 */
object ChatSnippetExtractor {

    /** Characters of one snippet when the caller does not override it. */
    const val DEFAULT_MAX_CHARS = 120

    /**
     * Flattens newlines to spaces, trims, and returns at most [maxChars] characters around the first
     * case-insensitive occurrence of [query]. No occurrence (e.g. a semantic-only match) yields the
     * head of the text. A leading/trailing `…` marks a cut on that side.
     */
    fun extract(text: String, query: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (flat.isEmpty()) return ""
        if (maxChars <= 0 || flat.length <= maxChars) return flat

        val trimmedQuery = query.trim()
        val index = if (trimmedQuery.isEmpty()) -1 else flat.indexOf(trimmedQuery, ignoreCase = true)
        if (index < 0) return flat.take(maxChars).trimEnd() + "…"

        var start = index - maxChars / 3
        if (start < 0) start = 0
        var end = start + maxChars
        if (end > flat.length) {
            end = flat.length
            start = (end - maxChars).coerceAtLeast(0)
        }
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end).trim() + suffix
    }
}
