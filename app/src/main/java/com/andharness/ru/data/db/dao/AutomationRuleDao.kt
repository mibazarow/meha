package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.andharness.ru.data.db.entity.AutomationRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationRuleDao {

    @Query("SELECT * FROM automation_rules ORDER BY id")
    fun observeAll(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules ORDER BY id")
    suspend fun getAll(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun getById(id: Long): AutomationRuleEntity?

    @Insert
    suspend fun insert(entity: AutomationRuleEntity): Long

    @Update
    suspend fun update(entity: AutomationRuleEntity)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE automation_rules SET lastRunAt = :timestamp WHERE id = :id")
    suspend fun setLastRun(id: Long, timestamp: Long)

    /**
     * Atomically claims the occurrence [slot] for `id`: sets `lastRunAt = :slot` only when the rule
     * has not already run that occurrence (`lastRunAt` is null until the first run). Returns `1`
     * when this caller claimed it and `0` when another delivery already did, so a re-delivered
     * WorkManager job cannot fire the same rule twice.
     */
    @Query(
        "UPDATE automation_rules SET lastRunAt = :slot WHERE id = :id " +
            "AND (lastRunAt IS NULL OR lastRunAt < :slot)"
    )
    suspend fun claimRun(id: Long, slot: Long): Int
}
