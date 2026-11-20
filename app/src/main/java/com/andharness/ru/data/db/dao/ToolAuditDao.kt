package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.ToolAuditEntity

/**
 * Append-only DAO for the tool-call audit log (update.md idea #6). Rows are never updated, and the
 * hash chain is verified by reading [getAll] in id order. The only deletes are the two retention
 * prunes below, which drop the oldest rows so the table stays bounded (see
 * `ToolAuditRepository.prune`); they never touch newer rows.
 *
 * Official reference: https://developer.android.com/training/data-storage/room/accessing-data
 */
@Dao
interface ToolAuditDao {

    @Insert
    suspend fun insert(entity: ToolAuditEntity): Long

    /** Whole chain, oldest first — the order [com.andharness.ru.domain.audit.ToolAuditChain.verify] expects. */
    @Query("SELECT * FROM tool_audit ORDER BY id ASC")
    suspend fun getAll(): List<ToolAuditEntity>

    /** Newest-first page for reads/export previews. */
    @Query("SELECT * FROM tool_audit ORDER BY id DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<ToolAuditEntity>

    /** Hash of the current tail, or null when the log is empty (the genesis case). */
    @Query("SELECT hash FROM tool_audit ORDER BY id DESC LIMIT 1")
    suspend fun lastHash(): String?

    @Query("SELECT COUNT(*) FROM tool_audit")
    suspend fun count(): Int

    /** Deletes rows older than [cutoff] (the `at` index makes this cheap). Returns removed count. */
    @Query("DELETE FROM tool_audit WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Keeps only the newest [keep] rows, deleting everything older. The sub-select is ordered by the
     * same key as [getLatest], so "newest [keep]" is deterministic.
     */
    @Query(
        "DELETE FROM tool_audit WHERE id NOT IN " +
            "(SELECT id FROM tool_audit ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun pruneToLast(keep: Int): Int
}
