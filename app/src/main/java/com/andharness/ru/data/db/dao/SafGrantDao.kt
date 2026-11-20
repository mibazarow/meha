package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.SafGrantEntity

/** Room access to persisted SAF tree grants (table `saf_grants`). */
@Dao
interface SafGrantDao {
    @Insert
    suspend fun insert(entity: SafGrantEntity): Long

    @Query("SELECT * FROM saf_grants ORDER BY createdAt DESC")
    suspend fun all(): List<SafGrantEntity>

    @Query("SELECT * FROM saf_grants WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SafGrantEntity?

    @Query("SELECT * FROM saf_grants WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): SafGrantEntity?

    @Query("DELETE FROM saf_grants WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE saf_grants SET lastCheckedAt = :ts, lastOk = :ok WHERE id = :id")
    suspend fun markChecked(id: Long, ts: Long, ok: Boolean)
}
