package com.andharness.ru.domain.person

import com.andharness.ru.domain.formatting.collapseWhitespace

/**
 * Bounded, neutral "gist" of an ingested message (update.md §4.4/§4.9).
 *
 * The personal base stores **only metadata plus a short summary** and never the body of a message.
 * This object is the single place that produces that summary, so the rule is enforced at ingest time
 * and is unit-testable on the JVM (no Android runtime):
 *
 * - whitespace is collapsed and the text is trimmed, so a stray multi-line body cannot smuggle
 *   formatting or huge blocks;
 * - the result is at most [MAX_CHARS] characters;
 * - when it had to be cut, the last character is an ellipsis (`…`) that is **inside** the budget.
 *
 * A blank input yields `null` (nothing to store) rather than an empty string.
 */
object MessageGist {

    /** Hard cap of the persisted gist, in characters (update.md §4.4, "короткий summary"). */
    const val MAX_CHARS = 80

    /** Character appended when the gist had to be truncated; counts towards [MAX_CHARS]. */
    private const val ELLIPSIS = '…'

    /**
     * Returns the bounded gist of [raw], or `null` when there is no meaningful text.
     *
     * Guarantees `result.length <= MAX_CHARS`.
     */
    fun bounded(raw: String?): String? {
        val collapsed = raw?.let { collapseWhitespace(it) }.orEmpty()
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= MAX_CHARS) return collapsed
        // Reserve one character for the ellipsis so the total never exceeds the cap.
        return collapsed.take(MAX_CHARS - 1).trimEnd() + ELLIPSIS
    }
}
