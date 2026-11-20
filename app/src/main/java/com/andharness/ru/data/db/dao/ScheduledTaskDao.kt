package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.andharness.ru.data.db.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    /**
     * One-shot reminders ([atMs] non-null) come first, ordered by their due instant; recurring
     * tasks follow, ordered by wall-clock time as before.
     */
    @Query(
        "SELECT * FROM scheduled_tasks " +
            "ORDER BY CASE WHEN atMs IS NULL THEN 1 ELSE 0 END, atMs, hour, minute, id"
    )
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    /** Same ordering as [observeAll]: one-shots by [atMs], then recurring tasks by HH:MM. */
    @Query(
        "SELECT * FROM scheduled_tasks " +
            "ORDER BY CASE WHEN atMs IS NULL THEN 1 ELSE 0 END, atMs, hour, minute, id"
    )
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: Long): ScheduledTaskEntity?

    @Insert
    suspend fun insert(entity: ScheduledTaskEntity): Long

    @Update
    suspend fun update(entity: ScheduledTaskEntity)

    @Delete
    suspend fun delete(entity: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE scheduled_tasks SET lastRunAt = :timestamp WHERE id = :id")
    suspend fun setLastRun(id: Long, timestamp: Long)

    /**
     * Atomically claims the occurrence [slot] for `id`: sets `lastRunAt = :slot` only when the row
     * has not already run that occurrence. Returns `1` when this caller claimed it and `0` when
     * another delivery already did (or the row is gone), so a re-delivered WorkManager job cannot
     * execute the same slot twice.
     */
    @Query("UPDATE scheduled_tasks SET lastRunAt = :slot WHERE id = :id AND lastRunAt < :slot")
    suspend fun claimRun(id: Long, slot: Long): Int
}
