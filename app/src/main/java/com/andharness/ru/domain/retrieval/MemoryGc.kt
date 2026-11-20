package com.andharness.ru.domain.retrieval

/**
 * Pure GC decision for stale long-term memories.
 *
 * Only `importance == 0` rows are eligible: importance 1 (important) and 2 (critical) are never
 * collected, however old. A row is stale when its decay reference — the last recall [lastUsedAt],
 * or [createdAt] before the first recall — is older than [STALE_AFTER_MS] (180 days). A row with an
 * unknown timestamp (0) is kept, because staleness cannot be proven.
 *
 * The worker applies this in bounded batches through `AssistRepository.deleteMemory`, i.e. the same
 * `kind = "memory"`-scoped path `memory_delete` uses, so a note row can never be removed.
 */
object MemoryGc {

    /** An untouched importance-0 memory older than this is eligible for collection (180 days). */
    const val STALE_AFTER_MS = 180L * 24 * 60 * 60 * 1000

    /**
     * True when the memory may be pruned: importance 0 AND untouched for more than [STALE_AFTER_MS].
     * The age uses [lastUsedAt] when it was ever recalled, otherwise [createdAt].
     */
    fun shouldGc(importance: Int, lastUsedAt: Long, createdAt: Long, now: Long): Boolean {
        if (importance > 0) return false
        val reference = if (lastUsedAt > 0L) lastUsedAt else createdAt
        if (reference <= 0L) return false
        return now - reference > STALE_AFTER_MS
    }
}
