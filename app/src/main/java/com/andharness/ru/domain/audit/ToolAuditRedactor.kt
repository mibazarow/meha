package com.andharness.ru.domain.audit

/**
 * Redaction/preview helper for the tool-call audit log (update.md idea #6).
 *
 * The audit log must never become a secret store: before an argument string is persisted it is
 * scanned for obvious secret-bearing keys (`api_key`, `token`, `password`, …) and bearer/openai-style
 * tokens, the values are replaced with a mask, and the result is truncated. Pure `Regex`/`String`
 * code — unit-testable on the JVM.
 */
object ToolAuditRedactor {

    /** Longest stored preview; longer argument strings are elided with an ellipsis. */
    const val MAX_PREVIEW_LENGTH = 500

    const val MASK = "‹скрыто›"

    /** `key: value` / `key=value` where the key looks secret-bearing; the value is masked. */
    private val SECRET_KEY_VALUE = Regex(
        "(?i)(\"?(?:api[_-]?key|apikey|token|access[_-]?token|refresh[_-]?token|auth[_-]?token|" +
            "password|passwd|pass|secret|client[_-]?secret|private[_-]?key|authorization|auth)\"?" +
            "\\s*[:=]\\s*\"?)([^\"\\n]+)",
        RegexOption.IGNORE_CASE
    )

    private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+", RegexOption.IGNORE_CASE)

    private val OPENAI_KEY = Regex("sk-[A-Za-z0-9]{8,}")

    /** Masks secret-looking values in [raw]; returns [raw] unchanged when nothing matches. */
    fun redact(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = SECRET_KEY_VALUE.replace(raw) { match -> match.groupValues[1] + MASK }
        out = BEARER.replace(out, "Bearer $MASK")
        out = OPENAI_KEY.replace(out, MASK)
        return out
    }

    /** Redacted and truncated argument preview safe to persist and export. */
    fun preview(argsJson: String): String {
        val redacted = redact(argsJson)
        return if (redacted.length > MAX_PREVIEW_LENGTH) {
            redacted.take(MAX_PREVIEW_LENGTH) + "…"
        } else {
            redacted
        }
    }
}
