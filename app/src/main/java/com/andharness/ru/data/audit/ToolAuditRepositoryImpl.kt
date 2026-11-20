package com.andharness.ru.data.audit

import com.andharness.ru.data.db.dao.ToolAuditDao
import com.andharness.ru.data.db.entity.ToolAuditEntity
import com.andharness.ru.domain.audit.ToolAuditChain
import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.audit.ToolAuditRedactor
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.audit.ToolAuditRetention
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of the append-only tool-call audit log (update.md idea #6).
 *
 * [record] takes a [Mutex] so concurrent appends cannot read the same tail hash and fork the chain;
 * the entry hash is computed over the redacted preview and only the result's SHA-256 is stored.
 * Bounded by [ToolAuditRetention]: at most 200 newest rows, nothing older than 30 days.
 */
@Singleton
class ToolAuditRepositoryImpl @Inject constructor(
    private val dao: ToolAuditDao
) : ToolAuditRepository {

    private val appendLock = Mutex()

    override suspend fun record(
        at: Long,
        chatId: Long?,
        tool: String,
        argsJson: String,
        success: Boolean,
        resultText: String,
        durationMs: Long,
        model: String?
    ): Long {
        val resultHash = ToolAuditChain.sha256Hex(resultText)
        val argsPreview = ToolAuditRedactor.preview(argsJson)
        return appendLock.withLock {
            val prevHash = dao.lastHash() ?: ToolAuditChain.GENESIS_PREV_HASH
            val draft = ToolAuditEntry(
                id = 0,
                at = at,
                chatId = chatId,
                tool = tool,
                argsPreview = argsPreview,
                success = success,
                resultHash = resultHash,
                durationMs = durationMs,
                model = model,
                prevHash = prevHash,
                hash = ""
            )
            val hash = ToolAuditChain.computeHash(draft)
            dao.insert(draft.toEntity(hash))
        }
    }

    override suspend fun recent(limit: Int): List<ToolAuditEntry> =
        dao.getLatest(limit.coerceAtLeast(1)).map { it.toDomain() }

    override suspend fun all(): List<ToolAuditEntry> = dao.getAll().map { it.toDomain() }

    override suspend fun count(): Int = dao.count()

    override suspend fun verify(): ToolAuditChain.VerifyResult {
        val entries = all()
        // Retention prunes the oldest rows, so the retained head no longer links to genesis; anchor
        // verification on its stored prevHash to check every retained link without a false break.
        val anchor = entries.firstOrNull()?.prevHash ?: ToolAuditChain.GENESIS_PREV_HASH
        return ToolAuditChain.verify(entries, anchorPrevHash = anchor)
    }

    override suspend fun prune(now: Long) {
        dao.deleteOlderThan(ToolAuditRetention.cutoff(now))
        dao.pruneToLast(ToolAuditRetention.MAX_ENTRIES)
    }

    private fun ToolAuditEntry.toEntity(hash: String) = ToolAuditEntity(
        at = at,
        chatId = chatId,
        tool = tool,
        argsPreview = argsPreview,
        success = success,
        resultHash = resultHash,
        durationMs = durationMs,
        model = model,
        prevHash = prevHash,
        hash = hash
    )

    private fun ToolAuditEntity.toDomain() = ToolAuditEntry(
        id = id,
        at = at,
        chatId = chatId,
        tool = tool,
        argsPreview = argsPreview,
        success = success,
        resultHash = resultHash,
        durationMs = durationMs,
        model = model,
        prevHash = prevHash,
        hash = hash
    )
}
