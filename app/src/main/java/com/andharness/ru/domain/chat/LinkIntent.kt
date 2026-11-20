package com.andharness.ru.domain.chat

import android.content.Intent
import android.net.Uri

/**
 * Scheme allow-list policy for links tapped inside chat bubbles.
 *
 * A chat reply is model-authored text, so a link destination is untrusted input: rendering it as a
 * `ClickableSpan` and blindly firing `Intent(ACTION_VIEW, Uri.parse(link))` would let a
 * `javascript:`/`data:`/`file:` destination reach an Activity. This object decides — purely, on the
 * raw string, without any Android runtime — whether a destination may be opened, and builds the
 * `ACTION_VIEW` intent only for the allow-listed schemes.
 * `Intent.ACTION_VIEW` + `Uri.parse` —
 * https://developer.android.com/reference/android/content/Intent#ACTION_VIEW
 * https://developer.android.com/reference/android/net/Uri#parse(java.lang.String)
 *
 * The scheme grammar is RFC 3986 §3.1 (`ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )` followed by
 * `:`) — https://www.rfc-editor.org/rfc/rfc3986#section-3.1 — so a destination with no scheme
 * (`example.com`) or a digit-leading pseudo-scheme (`1http://x`) is rejected outright.
 */
object LinkIntent {

    /**
     * Only these schemes are handed to `ACTION_VIEW`: web links, e-mail and phone dialers. Anything
     * else (`javascript:`, `data:`, `file:`, `content:`, `intent:`, …) is refused — the same
     * conservative stance Markwon documents for its default resolver
     * (https://noties.io/Markwon/docs/v4/core/configuration.html#linkresolver).
     */
    val ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto", "tel")

    // RFC 3986 scheme grammar: leading ALPHA, then ALPHA/DIGIT/+/-/. and the terminating ':'.
    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    /**
     * Lower-cased scheme of [url] (case-insensitive per RFC 3986 §6.2.3), or `null` when the string
     * does not start with a syntactically valid `scheme:` prefix.
     */
    fun scheme(url: String): String? {
        val trimmed = url.trim()
        val match = SCHEME_PREFIX.find(trimmed) ?: return null
        return match.value.dropLast(1).lowercase()
    }

    /** True when [url] may be opened: allow-listed scheme, non-blank, no embedded whitespace/controls. */
    fun isSafe(url: String): Boolean = sanitize(url) != null

    /**
     * Returns the trimmed, openable destination, or `null` when the link must not be opened.
     *
     * Whitespace and control characters inside the destination are rejected: they are the classic
     * way a "malformed" URL smuggles a second token into a downstream parser. For `http`/`https`
     * an authority is required (`//` plus at least one character), so bare `http://` is rejected;
     * `mailto:`/`tel:` only need a non-empty rest (`mailto:user@example.com`, `tel:+70000000000`).
     */
    fun sanitize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return null

        val scheme = scheme(trimmed) ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null

        val rest = trimmed.substringAfter(':')
        return when (scheme) {
            "http", "https" -> if (rest.startsWith("//") && rest.length > 2) trimmed else null
            else -> if (rest.isNotEmpty()) trimmed else null
        }
    }

    /**
     * Builds the `ACTION_VIEW` intent for [url], or `null` when [sanitize] rejects it (unknown
     * scheme, malformed, blank). The caller (`SafeLinkResolver`) still wraps `startActivity` in a
     * try/catch because no Activity may exist for a well-formed `mailto:`/`tel:` on a given device.
     */
    fun buildViewIntent(url: String): Intent? {
        val safe = sanitize(url) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(safe))
    }
}
