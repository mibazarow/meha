package com.andharness.ru.domain.automation

/**
 * Persistence for the firing-event log (Room v27, table `automation_events`).
 *
 * Writers call [record] exactly once per rule that actually fired; readers (the `events_recent`
 * tool, the UI) get newest-first rows. [prune] is the single place that enforces
 * [AutomationEventRetention], so every writer stays honest about how much history is kept.
 */
interface AutomationEventRepository {

    /** Persists one fired event; returns the new row id. Never called for non-firing sensors. */
    suspend fun record(event: AutomationEvent, rule: AutomationRule): Long

    /** Newest-first list, capped at [limit]. */
    suspend fun recent(limit: Int = AutomationEventRetention.DEFAULT_LIMIT): List<AutomationEventRecord>

    /** Newest-first list filtered to [type], capped at [limit]. */
    suspend fun recentByType(
        type: String,
        limit: Int = AutomationEventRetention.DEFAULT_LIMIT
    ): List<AutomationEventRecord>

    /** Enforces the retention policy (last [AutomationEventRetention.MAX_ENTRIES], not older than the TTL). */
    suspend fun prune(now: Long = System.currentTimeMillis())
}
