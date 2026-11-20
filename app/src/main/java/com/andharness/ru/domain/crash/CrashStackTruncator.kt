package com.andharness.ru.domain.crash

/**
 * Pure, testable truncation of a crash stack trace before it is persisted (update.md §1.4).
 *
 * A crash log is a bounded diagnostic store; an uncaught exception can carry an arbitrarily large
 * stack (deep recursion, huge causes chain), so the stored text is capped at
 * [MAX_CHARS] characters (≈8 KB) and the truncation is made explicit to the reader.
 */
object CrashStackTruncator {

    /** Hard cap on the stored stack text: 8 KB. */
    const val MAX_CHARS = 8 * 1024

    private const val MARKER = "\n… [stack truncated]"

    /**
     * Returns [stack] unchanged when it already fits, otherwise the first
     * `maxChars - marker.length` characters followed by an explicit truncation marker. The result
     * never exceeds [maxChars].
     */
    fun truncate(stack: String?, maxChars: Int = MAX_CHARS): String {
        val value = stack.orEmpty()
        if (maxChars <= 0) return ""
        if (value.length <= maxChars) return value
        val marker = if (MARKER.length < maxChars) MARKER else MARKER.take(maxChars)
        val head = maxChars - marker.length
        return value.take(head) + marker
    }
}
