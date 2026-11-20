package com.andharness.ru.domain.notify


/**
 * Bounded, thread-safe, in-memory ring buffer of the most recent triaged notifications
 * (update.md idea #17).
 *
 * It is intentionally **volatile**: nothing here is written to disk, so the capture buffer cannot
 * outlive the process and cannot become a hidden message archive. The digest is honest about it:
 * when the buffer is empty (feature off / process restarted) it says so instead of inventing data.
 */
class NotificationCaptureLog {

    private val lock = Any()
    private val entries = ArrayDeque<CapturedNotification>()

    fun add(entry: CapturedNotification) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** Newest first, optionally only entries at/after [since] (epoch millis). */
    fun recent(since: Long? = null, limit: Int = MAX_ENTRIES): List<CapturedNotification> {
        val bounded = limit.coerceIn(1, MAX_ENTRIES)
        return synchronized(lock) {
            val filtered = when (since) {
                null -> entries.toList()
                else -> entries.filter { it.at >= since }
            }
            filtered.asReversed().take(bounded)
        }
    }

    /** How many captured events came from [app]+[sender] at/after [sinceMs]. */
    fun countFrom(app: String, sender: String?, sinceMs: Long): Int = synchronized(lock) {
        entries.count {
            it.app == app && it.sender == sender && it.at >= sinceMs
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        const val MAX_ENTRIES = 200
    }
}
