package com.andharness.ru.domain.automation

/**
 * Pure retention/limit policy for the persistent automation event log.
 *
 * The rule is deliberately blunt and cheap: keep at most the last [MAX_ENTRIES] firing events and
 * nothing older than [MAX_AGE_MS] (14 days). Events that actually fire an automation are rare by
 * nature (a noisy rule is auto-disabled by [RuleRateLimiter]), so this is generous for «что было
 * ночью?» while keeping the table bounded.
 */
object AutomationEventRetention {

    /** Hard cap on retained firing events. */
    const val MAX_ENTRIES = 500

    /** Retention TTL: events older than 14 days are pruned. */
    const val MAX_AGE_MS = 14L * 24L * 60L * 60L * 1_000L

    /** Default number of rows a tool read returns. */
    const val DEFAULT_LIMIT = 20

    /** Upper bound a caller may request, so one read cannot dump the whole log. */
    const val MAX_LIMIT = 100

    /** Rows older than this instant must be deleted. */
    fun cutoff(now: Long): Long = now - MAX_AGE_MS

    /** Bounds a requested `limit` (null/absent ⇒ [DEFAULT_LIMIT]) into `1..[MAX_LIMIT]`. */
    fun bound(limit: Int?): Int = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
}
