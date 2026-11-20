package com.andharness.ru.domain.notify


/**
 * Bounded, thread-safe record of the last [source]+[summary] gists that were already memorized, so
 * the same notification re-posted within [windowMs] does not produce a second memory note.
 */
class RecentNoteDedup(
    private val windowMs: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    private val lock = Any()
    private val recent = ArrayDeque<Pair<String, Long>>()

    /** True when an identical gist from the same source was memorized inside the window. */
    fun isDuplicate(source: String, summary: String, now: Long): Boolean {
        val key = NotificationTriage.dedupeKey(source, summary)
        return synchronized(lock) {
            recent.any { it.first == key && now - it.second in 0..windowMs }
        }
    }

    /** Remembers a gist; the buffer is bounded so memory use cannot grow without limit. */
    fun remember(source: String, summary: String, now: Long) {
        val key = NotificationTriage.dedupeKey(source, summary)
        synchronized(lock) {
            recent.addLast(key to now)
            while (recent.size > maxEntries) recent.removeFirst()
        }
    }

    /** Test/utility hook: forgets everything. */
    fun clear() {
        synchronized(lock) { recent.clear() }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}
