package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.MemoryVectorEntity

/**
 * DAO for the dense memory-embedding store. One row per memory, keyed by the
 * `notes.id` of that memory; a re-embed replaces the row ([upsert] with REPLACE).
 */
@Dao
interface MemoryVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MemoryVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MemoryVectorEntity>)

    @Query("SELECT * FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun get(memoryId: Long): MemoryVectorEntity?

    @Query("SELECT * FROM memory_vectors WHERE memoryId IN (:memoryIds)")
    suspend fun byIds(memoryIds: List<Long>): List<MemoryVectorEntity>

    @Query("SELECT COUNT(*) FROM memory_vectors")
    suspend fun count(): Int

    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    suspend fun delete(memoryId: Long)

    @Query("DELETE FROM memory_vectors")
    suspend fun deleteAll()
}
