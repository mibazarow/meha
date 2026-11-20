package com.andharness.ru.data.tools.ui

/**
 * Pure helpers behind the `remember_screen` tool: flatten the accessibility text lines of the
 * current screen (trim, drop blanks, remove consecutive duplicates, keep reading order, bound the
 * result) and build the memory note text/tags. Deliberately Android-free so the whole algorithm is
 * unit-tested on the JVM.
 *
 * The raw lines come from `AccessibilityNodeInfo.getText()` (falling back to
 * `getContentDescription()`) over the app window picked from `AccessibilityService.getWindows()`.
 * Official references:
 * - `AccessibilityNodeInfo.getText()` —
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getText()
 * - `AccessibilityNodeInfo.getContentDescription()` —
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getContentDescription()
 * - `AccessibilityService.getWindows()` —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()
 */
object ScreenRememberSupport {

    /** Default character budget of the captured screen text. */
    const val DEFAULT_MAX_CHARS = 4000

    /** Hard bounds applied to `max_chars`, so a huge screen can never blow up the tool result. */
    const val MIN_MAX_CHARS = 200
    const val MAX_MAX_CHARS = 20000

    /** Always attached to a saved memory, so `remember_screen` notes stay findable. */
    const val REMEMBER_TAG = "remember_screen"

    /** Bounded, de-duplicated screen text plus the number of lines and whether it had to be cut. */
    data class ScreenText(
        val text: String,
        val lines: Int,
        val truncated: Boolean,
    )

    /**
     * `max_chars` argument → a bounded positive int. A missing/invalid value yields
     * [DEFAULT_MAX_CHARS]; anything outside [[MIN_MAX_CHARS], [MAX_MAX_CHARS]] is clamped.
     */
    fun parseMaxChars(raw: String?): Int {
        val parsed = raw?.trim()?.toIntOrNull() ?: return DEFAULT_MAX_CHARS
        return parsed.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
    }

    /**
     * Trims each line, drops blanks and consecutive duplicates, keeps reading order, joins the kept
     * lines with a newline and bounds the whole result to [maxChars] characters.
     *
     * [ScreenText.truncated] is `true` when a non-empty, non-duplicate line was cut to fit or had to
     * be dropped because the budget was already full — never when the text happened to fit exactly.
     */
    fun flatten(rawTexts: List<String?>, maxChars: Int): ScreenText {
        val limit = maxChars.coerceAtLeast(1)
        val sb = StringBuilder()
        var last: String? = null
        var lines = 0
        var truncated = false
        for (raw in rawTexts) {
            val line = raw?.trim().orEmpty()
            if (line.isEmpty() || line == last) continue
            val separator = if (sb.isEmpty()) 0 else 1
            if (sb.length + separator >= limit) {
                truncated = true
                break
            }
            val room = limit - sb.length - separator
            if (line.length > room) {
                if (separator == 1) sb.append('\n')
                sb.append(line.substring(0, room))
                lines++
                truncated = true
                break
            }
            if (separator == 1) sb.append('\n')
            sb.append(line)
            lines++
            last = line
        }
        return ScreenText(sb.toString(), lines, truncated)
    }

    /**
     * Memory note text: an optional short user hint/title prefixing the captured [screenText]. A
     * blank/absent [note] leaves the screen text as the whole note.
     */
    fun buildNoteText(note: String?, screenText: String): String {
        val title = note?.trim()?.takeIf { it.isNotEmpty() }
        val body = screenText.trim()
        return if (title == null) body else "$title: $body"
    }

    /**
     * Memory tags, order-preserving and de-duplicated: always [REMEMBER_TAG], plus
     * `screen:<package>` when a package is known, plus `person:<id>` when a person was resolved,
     * plus every comma-separated [userTags] entry.
     */
    fun buildTags(userTags: String?, packageName: String?, personId: Long?): String {
        val out = LinkedHashSet<String>()
        out += REMEMBER_TAG
        packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { out += "screen:$it" }
        personId?.let { out += "person:$it" }
        userTags.orEmpty().split(',').forEach { tag ->
            tag.trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.joinToString(",")
    }
}
