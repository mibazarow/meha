package com.andharness.ru.domain.agent

/**
 * Machine-readable first line for every tool result fed back to the model (TOOLS_TASKS §1).
 *
 * Tool handlers report their outcome in prose, so the model has to guess success from wording — and
 * guesses wrong ("Файл записан" vs a stack trace in a string both read as text). The envelope makes
 * the outcome explicit and unambiguous: `exit 0` on success, `exit N` on failure, body after it.
 *
 * The envelope is applied at the LAST mile only — when the result becomes a `tool` message in the
 * model's working history. The persisted chat row, the audit log and every internal parser keep the
 * raw output, so nothing that already reads tool text has to change. [splitExit] is the single place
 * that strips it, used by [com.andharness.ru.data.agent.ToolOutputLimiter] so truncation can never
 * cut the first line.
 */
object ToolResultEnvelope {

    /** Exit code of a successful tool call. */
    const val EXIT_OK = 0

    /** Exit code of a failed tool call. */
    const val EXIT_FAILURE = 1

    private const val PREFIX = "exit "

    /** Wraps [body] with the exit line for [success]. */
    fun wrap(success: Boolean, body: String): String =
        envelope(if (success) EXIT_OK else EXIT_FAILURE, body)

    /** Wraps [body] with an explicit [code] (kept verbatim by [splitExit]). */
    fun envelope(code: Int, body: String): String = "$PREFIX$code\n$body"

    /**
     * Splits a possibly enveloped text into `(exitCode, body)`. Text without a valid envelope (a
     * legacy/raw tool output, or prose that merely starts with "exit") returns `null` for the code
     * and the ORIGINAL text as the body, so callers never lose content.
     */
    fun splitExit(text: String): Pair<Int?, String> {
        val newline = text.indexOf('\n')
        val firstLine = if (newline >= 0) text.substring(0, newline) else text
        if (!firstLine.startsWith(PREFIX)) return null to text
        val code = firstLine.removePrefix(PREFIX).trim().toIntOrNull() ?: return null to text
        val body = if (newline >= 0) text.substring(newline + 1) else ""
        return code to body
    }
}
