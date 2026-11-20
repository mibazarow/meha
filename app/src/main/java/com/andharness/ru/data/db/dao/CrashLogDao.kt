package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.CrashLogEntity

/**
 * Bounded crash-log store (update.md §1.4). All reads are ordered newest-first; retention is
 * enforced by [deleteOlderThan] + [deleteBeyondLimit] (see
 * `CrashLogRepository.prune`).
 */
@Dao
interface CrashLogDao {

    @Insert
    suspend fun insert(entity: CrashLogEntity): Long

    @Query("SELECT * FROM crash_logs ORDER BY at DESC, id DESC")
    suspend fun getAll(): List<CrashLogEntity>

    @Query("SELECT * FROM crash_logs ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<CrashLogEntity>

    @Query("SELECT * FROM crash_logs ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun getMostRecent(): CrashLogEntity?

    @Query("SELECT COUNT(*) FROM crash_logs")
    suspend fun count(): Int

    @Query("DELETE FROM crash_logs")
    suspend fun deleteAll()

    @Query("DELETE FROM crash_logs WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Keeps only the newest [keep] rows, deleting everything older. The sub-select is ordered by
     * the same key as the reader, so "newest [keep]" is deterministic.
     */
    @Query(
        "DELETE FROM crash_logs WHERE id NOT IN " +
            "(SELECT id FROM crash_logs ORDER BY at DESC, id DESC LIMIT :keep)"
    )
    suspend fun deleteBeyondLimit(keep: Int): Int
}
