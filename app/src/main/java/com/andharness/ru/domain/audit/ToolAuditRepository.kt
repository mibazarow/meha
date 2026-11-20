package com.andharness.ru.domain.audit

/**
 * Append-only persistence for the tool-call audit log (update.md idea #6).
 *
 * There is deliberately no `update`: existing rows never change, so the hash chain stays verifiable.
 * The only deletion is bounded retention via [prune], which drops the oldest rows once the log
 * exceeds [ToolAuditRetention.MAX_ENTRIES] / [ToolAuditRetention.MAX_AGE_MS]. [record] computes the
 * next link from the current tail under a lock; [verify] recomputes the retained chain.
 */
interface ToolAuditRepository {

    /**
     * Appends one entry and returns its DB id. [argsJson] is redacted/truncated before storage and
     * [resultText] is reduced to its SHA-256. Implementations must serialize appends so the hash
     * chain cannot fork.
     */
    suspend fun record(
        at: Long,
        chatId: Long?,
        tool: String,
        argsJson: String,
        success: Boolean,
        resultText: String,
        durationMs: Long,
        model: String?
    ): Long

    /** Newest-first list, capped at [limit]. */
    suspend fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<ToolAuditEntry>

    /** The whole chain in storage order (oldest first), for verification and export. */
    suspend fun all(): List<ToolAuditEntry>

    suspend fun count(): Int

    /** Recomputes the hash chain; reports the first broken index, if any. */
    suspend fun verify(): ToolAuditChain.VerifyResult

    /**
     * Enforces the retention policy (newest [ToolAuditRetention.MAX_ENTRIES] rows, nothing older
     * than [ToolAuditRetention.MAX_AGE_MS]). Drops the oldest rows only; callers must not assume the
     * chain still links to genesis afterwards.
     */
    suspend fun prune(now: Long = System.currentTimeMillis())

    companion object {
        const val DEFAULT_RECENT_LIMIT = 100
    }
}
