package com.andharness.ru.data.tools.saf

/**
 * Android-free normalization of Storage Access Framework URI strings.
 *
 * A grant is stored as the exact string returned by `ACTION_OPEN_DOCUMENT_TREE`, while the URI
 * reported back by `ContentResolver.getPersistedUriPermissions()` may differ only in
 * percent-encoding (`%3A` vs `:`) or in a trailing slash. Comparing the raw strings therefore
 * produces false negatives; [normalize] puts both sides into one canonical form so the stored
 * grant and the persisted permission can be matched reliably.
 *
 * This object deliberately has no Android imports so it can be covered by JVM unit tests. It is
 * used by `SafToolsHandler` for the `persistedUriPermissions` availability check.
 *
 * References:
 * - `ContentResolver.getPersistedUriPermissions()` —
 *   https://developer.android.com/reference/android/content/ContentResolver#getPersistedUriPermissions()
 * - `Uri.getPathSegments()` / URI encoding —
 *   https://developer.android.com/reference/android/net/Uri
 */
internal object SafUriNormalizer {

    /** True when [a] and [b] denote the same SAF URI after normalization. */
    fun matches(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /**
     * Canonicalizes a SAF URI: trims surrounding whitespace, percent-decodes every `%XX` escape
     * and removes trailing `/` characters. The `//` that follows the scheme is never removed.
     */
    fun normalize(raw: String): String = stripTrailingSlash(percentDecode(raw.trim()))

    private fun stripTrailingSlash(value: String): String {
        val schemeEnd = value.indexOf("://")
        // Keep at least the scheme plus its "//" (or one character for a non-hierarchical value).
        val minLength = if (schemeEnd >= 0) schemeEnd + 3 else 1
        var end = value.length
        while (end > minLength && value[end - 1] == '/') end--
        return value.substring(0, end)
    }

    private fun percentDecode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
