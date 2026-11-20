package com.andharness.ru.data.profile

import com.andharness.ru.domain.formatting.collapseWhitespace
import java.util.Locale

/**
 * Pure rules for the global profile: validation, text normalization, dedupe comparison and bounds.
 * Depends on nothing but the Kotlin standard library, so it is exercised on the JVM.
 */
object ProfileRules {

    /** Hard cap on the number of saved facts; the least recently written are dropped first. */
    const val MAX_ENTRIES = 100

    /** Hard cap on one fact's length (in characters); longer text is rejected (not silently cut). */
    const val MAX_TEXT_CHARS = 400

    /** Hard cap on the injected prompt block (in characters), so it can never blow up the context. */
    const val MAX_BLOCK_CHARS = 1200

    /** Label used in the system prompt for the always-on profile block. */
    const val HEADER =
        "ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ (сохранённые факты; используй, если уместно):"

    /**
     * Normalizes a fact's text: trims, collapses runs of whitespace (including newlines) to single
     * spaces and returns null when the result is blank or longer than [MAX_TEXT_CHARS]. A null result
     * means "reject honestly" — the text is never silently truncated.
     */
    fun normalizeText(raw: String?): String? {
        val collapsed = raw?.let { collapseWhitespace(it) }.orEmpty()
        if (collapsed.isEmpty()) return null
        if (collapsed.length > MAX_TEXT_CHARS) return null
        return collapsed
    }

    /** Case-insensitive comparison key for dedupe (the text is already normalized before compare). */
    fun sameText(a: String, b: String): Boolean =
        a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)

    /**
     * Drops blank/invalid entries and duplicates (same case-insensitive text, first kept), then keeps
     * at most [MAX_ENTRIES] newest entries. Used on load, so a hand-edited file can never exceed
     * the bounds or inject an oversized fact.
     */
    fun sanitize(entries: List<ProfileFact>): List<ProfileFact> {
        val seen = LinkedHashSet<String>()
        val result = ArrayList<ProfileFact>(entries.size)
        for (entry in entries) {
            val id = entry.id.trim()
            val text = normalizeText(entry.text) ?: continue
            if (id.isEmpty()) continue
            if (!seen.add(text.lowercase(Locale.ROOT))) continue
            result += entry.copy(id = id, text = text)
        }
        return if (result.size <= MAX_ENTRIES) result else result.takeLast(MAX_ENTRIES)
    }

    /**
     * Renders the prompt block: [HEADER] followed by one bounded `- [id] text` line per fact, in
     * storage order (oldest first). Returns "" when there are no facts (callers then inject nothing).
     * The whole block is hard-bounded to [limit] characters; if some facts do not fit, an honest
     * tail note says how many were omitted.
     */
    fun renderBlock(entries: List<ProfileFact>, limit: Int = MAX_BLOCK_CHARS): String {
        if (entries.isEmpty()) return ""
        val cap = limit
        if (cap <= 0) return ""
        if (HEADER.length >= cap) return HEADER.take(cap)
        val sb = StringBuilder(HEADER)
        var shown = 0
        for (entry in entries) {
            val line = "\n- [${entry.id}] ${entry.text}"
            if (sb.length + line.length > cap) break
            sb.append(line)
            shown++
        }
        if (shown < entries.size) {
            val note = "\n… ещё ${entries.size - shown} факт(ов) не показано."
            if (sb.length + note.length <= cap) sb.append(note)
        }
        return sb.toString()
    }
}
