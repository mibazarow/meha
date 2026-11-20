package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.andharness.ru.data.db.entity.LlmProviderEntity
import kotlinx.coroutines.flow.Flow

/** Saved LLM provider profiles, oldest first (stable list order in the UI). */
@Dao
interface LlmProviderDao {

    @Query("SELECT * FROM llm_providers ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<LlmProviderEntity>>

    @Query("SELECT * FROM llm_providers ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<LlmProviderEntity>

    @Query("SELECT * FROM llm_providers WHERE id = :id")
    suspend fun get(id: Long): LlmProviderEntity?

    @Query("SELECT COUNT(*) FROM llm_providers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: LlmProviderEntity): Long

    @Update
    suspend fun update(entity: LlmProviderEntity)

    @Query("DELETE FROM llm_providers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM llm_providers")
    suspend fun deleteAll()
}
