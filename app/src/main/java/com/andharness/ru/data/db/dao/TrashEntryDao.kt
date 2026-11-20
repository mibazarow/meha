package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.TrashEntryEntity

/** Room access to app-managed trash entries (table `trash_entries`). */
@Dao
interface TrashEntryDao {
    @Insert
    suspend fun insert(entity: TrashEntryEntity): Long

    @Query("SELECT * FROM trash_entries ORDER BY deletedAt DESC")
    suspend fun all(): List<TrashEntryEntity>

    @Query("SELECT * FROM trash_entries WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): TrashEntryEntity?

    @Query("DELETE FROM trash_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trash_entries")
    suspend fun clear()
}
