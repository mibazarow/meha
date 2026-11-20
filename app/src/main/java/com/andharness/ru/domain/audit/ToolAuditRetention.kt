package com.andharness.ru.domain.audit

/**
 * Pure retention/limit policy for the append-only tool-call audit log.
 *
 * The rule mirrors `AutomationEventRetention`: keep at most the newest [MAX_ENTRIES] rows and
 * nothing older than [MAX_AGE_MS] (30 days). The log is written on every tool dispatch, so without
 * a bound it would grow forever; the cap keeps only the last ~200 events useful for
 * «что агент делал?» while staying small.
 *
 * Note: pruning removes the oldest rows, so the retained hash chain no longer links back to genesis.
 * [ToolAuditChain.verify] anchors on the oldest retained row's `prevHash` to account for that.
 */
object ToolAuditRetention {

    /** Hard cap on retained audit rows: keep the newest ~200 events. */
    const val MAX_ENTRIES = 200

    /** Retention TTL: rows older than 30 days are pruned. */
    const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1_000L

    /** Rows written before this instant must be deleted. */
    fun cutoff(now: Long): Long = now - MAX_AGE_MS
}
