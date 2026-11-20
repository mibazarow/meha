package com.andharness.ru.data.agent

import com.andharness.ru.domain.agent.ToolResultEnvelope

/**
 * Bounds the size of a tool result fed back into the model's working history.
 *
 * Tool outputs (file contents, fetched pages, UI trees) can be far larger than the model's context
 * window. The full text is still persisted for the user-visible log; only the copy appended to the
 * working history is truncated — with an honest marker, so the model knows the output is incomplete
 * and can re-read a narrower slice instead of assuming it saw everything.
 *
 * The `exit N` envelope (TOOLS_TASKS §1) is never truncated: the machine-readable first line is what
 * tells the model whether the call worked, so it is split off, kept verbatim, and re-applied on top
 * of the bounded body.
 */
internal object ToolOutputLimiter {
    /** Maximum characters of one tool result sent back to the model. */
    const val MAX_CHARS: Int = 16_000

    fun limit(output: String): String {
        if (output.length <= MAX_CHARS) return output
        val (code, body) = ToolResultEnvelope.splitExit(output)
        val bounded = truncate(body)
        return if (code == null) bounded else ToolResultEnvelope.envelope(code, bounded)
    }

    private fun truncate(text: String): String {
        if (text.length <= MAX_CHARS) return text
        // Cut on a line boundary, never inside a line and never inside a surrogate pair: a model that
        // sees half a line assumes the output simply ended there.
        var cut = text.lastIndexOf('\n', MAX_CHARS)
        if (cut <= 0) cut = MAX_CHARS
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut--
        val shown = text.substring(0, cut)
        val hiddenChars = text.length - cut
        val hiddenLines = lineCount(text) - lineCount(shown)
        return buildString {
            append(shown)
            append("\n\n[вывод усечён: показаны первые ")
            append(shown.length)
            append(" символов (")
            append(lineCount(shown))
            append(" строк) из ")
            append(text.length)
            append("; скрыто ещё ")
            append(hiddenChars)
            append(" символов в ")
            append(hiddenLines)
            append(" строках. Продолжение: повтори вызов с бо́льшим offset (для files_read — ")
            append("смещение в байтах) или сузь запрос.]")
        }
    }

    private fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
}
