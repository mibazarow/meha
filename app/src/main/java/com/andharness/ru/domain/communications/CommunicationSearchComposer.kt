package com.andharness.ru.domain.communications

import com.andharness.ru.domain.formatting.collapseWhitespace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure merge/normalize helper for the `communication_search` tool.
 *
 * Communication hits arrive from three independent stores — transcript segments (FTS), the personal
 * base's `interactions` (SMS/calls/messenger/email/chat) and, best-effort, long-term memory — each
 * with its own shape, timestamps and possibly unbounded text. This object folds them into ONE
 * normalized, time-ordered, bounded list so the tool can answer with a single honest view of the
 * user's communication trail without ever flooding the context window.
 *
 * Guarantees (all pure and JVM-testable, no Android/Room):
 * - every item's text is whitespace-collapsed and cut to [SNIPPET_CHARS] characters;
 * - items without any usable text are dropped;
 * - the result is sorted by relevance ([Item.rank], higher first) then newest-first, then
 *   deterministically (channel → ref → text); unranked sources keep their previous newest-first
 *   order when nothing is ranked;
 * - the result is capped at [MAX_ITEMS] (an explicit [limit] can only lower it).
 *
 * Reference for the date formatter:
 * https://developer.android.com/reference/java/time/format/DateTimeFormatter
 */
object CommunicationSearchComposer {

    /** Hard cap on rendered hits, independent of the caller's requested limit. */
    const val MAX_ITEMS = 40

    /** Maximum length of a single normalized snippet, in characters. */
    const val SNIPPET_CHARS = 160

    private const val ELLIPSIS = '…'
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** One normalized communication hit, tagged with the channel it came from. */
    data class Item(
        /** When the hit happened (epoch millis). Transcripts use the recording's creation time. */
        val at: Long,
        /** Human-readable channel label, e.g. `РАСШИФРОВКА`, `SMS`, `CALL`, `MESSENGER`, `ПАМЯТЬ`. */
        val channel: String,
        /** Short context (person, recording name); may be blank. */
        val title: String,
        /** The normalized snippet. */
        val text: String,
        /** Bounded provenance, e.g. `recordings #7 [00:12-00:15]`; may be `null`. */
        val ref: String? = null,
        /**
         * Relevance hint used only for ordering: higher first, then recency. Transcript hits carry a
         * real score (lexical base and/or cosine); channels without a score stay at `0.0`, so when
         * nothing is ranked the merge is exactly the previous newest-first ordering.
         */
        val rank: Double = 0.0,
    )

    /** Collapses whitespace, trims and cuts [raw] to [SNIPPET_CHARS]; blank stays blank. */
    fun normalize(raw: String): String {
        val collapsed = collapseWhitespace(raw)
        if (collapsed.length <= SNIPPET_CHARS) return collapsed
        return collapsed.take(SNIPPET_CHARS - 1).trimEnd() + ELLIPSIS
    }

    /**
     * Normalizes each item, drops the ones with no usable text, sorts by relevance then newest-first
     * and caps the result. A non-positive [limit] yields an empty list (never the whole catalog).
     */
    fun merge(items: List<Item>, limit: Int = MAX_ITEMS): List<Item> {
        val cap = minOf(limit, MAX_ITEMS).coerceAtLeast(0)
        if (cap == 0) return emptyList()
        return items
            .mapNotNull { it.normalizedOrNull() }
            .sortedWith(
                compareByDescending<Item> { it.rank }
                    .thenByDescending { it.at }
                    .thenBy { it.channel }
                    .thenBy { it.ref.orEmpty() }
                    .thenBy { it.text }
            )
            .take(cap)
    }

    /**
     * Renders the merged [items] as a plain-text list (one `- [date] CHANNEL: …` line each).
     * An empty list renders the honest «ничего не найдено» line.
     */
    fun render(items: List<Item>, zone: ZoneId = ZoneId.systemDefault()): String {
        if (items.isEmpty()) return "Ничего не найдено."
        return buildString {
            items.forEachIndexed { index, item ->
                if (index > 0) append('\n')
                append("- [")
                append(STAMP.format(Instant.ofEpochMilli(item.at).atZone(zone)))
                append("] ")
                append(item.channel)
                append(": ")
                if (item.title.isNotBlank()) {
                    append(item.title)
                    append(" — ")
                }
                append(item.text)
                if (!item.ref.isNullOrBlank()) {
                    append(" (")
                    append(item.ref)
                    append(')')
                }
            }
        }
    }

    private fun Item.normalizedOrNull(): Item? {
        val normalizedText = normalize(text)
        if (normalizedText.isEmpty()) return null
        return copy(text = normalizedText, title = normalize(title), channel = channel.trim())
    }
}
