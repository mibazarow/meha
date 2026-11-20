package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.AutomationEventEntity

/**
 * Bounded store of fired automation events. All reads are newest-first; retention
 * is enforced by [deleteOlderThan] + [pruneToLast] (see
 * `AutomationEventRepository.prune`).
 *
 * Official reference: https://developer.android.com/training/data-storage/room/accessing-data
 */
@Dao
interface AutomationEventDao {

    @Insert
    suspend fun insert(entity: AutomationEventEntity): Long

    @Query("SELECT * FROM automation_events ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AutomationEventEntity>

    @Query(
        "SELECT * FROM automation_events WHERE eventType = :type " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recentByType(type: String, limit: Int): List<AutomationEventEntity>

    @Query("DELETE FROM automation_events WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Keeps only the newest [keep] rows, deleting everything older. The sub-select is ordered by
     * the same key as the reader, so "newest [keep]" is deterministic.
     */
    @Query(
        "DELETE FROM automation_events WHERE id NOT IN " +
            "(SELECT id FROM automation_events ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneToLast(keep: Int): Int
}
