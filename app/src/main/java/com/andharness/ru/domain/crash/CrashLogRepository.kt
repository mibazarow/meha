package com.andharness.ru.domain.crash

/**
 * Bounded persistence for crash logs (update.md §1.4).
 *
 * The store is deliberately limited: at most [MAX_ENTRIES] newest rows and nothing older than
 * [MAX_AGE_MS]. [prune] is the single place that enforces this, so every writer stays honest about
 * how much the app keeps on disk.
 */
interface CrashLogRepository {

    suspend fun record(log: CrashLog): Long

    /** Newest-first list, capped at [limit]. */
    suspend fun latest(limit: Int = MAX_ENTRIES): List<CrashLog>

    /** The single most recent crash, or null when the store is empty. */
    suspend fun mostRecent(): CrashLog?

    suspend fun count(): Int

    /** Deletes everything; returns the number of removed rows. */
    suspend fun clear(): Int

    /** Enforces the retention policy (last [MAX_ENTRIES], not older than [MAX_AGE_MS]). */
    suspend fun prune(now: Long)

    companion object {
        /** Retention: keep the last 50 crash logs (update.md §1.4). */
        const val MAX_ENTRIES = 50

        /** Retention TTL: delete crash logs older than 30 days (update.md §1.4). */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
