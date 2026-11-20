package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.DeviceFactEntity
import com.andharness.ru.data.db.entity.DeviceFactEventEntity

/** Room access to persisted device facts and their invalidation journal (tables `device_facts`, `device_fact_events`). */
@Dao
interface DeviceFactDao {

    /** Inserts or replaces the fact row with the same [DeviceFactEntity.key]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeviceFactEntity)

    @Query("SELECT * FROM device_facts WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): DeviceFactEntity?

    @Query("SELECT * FROM device_facts WHERE category = :category ORDER BY `key` ASC")
    suspend fun byCategory(category: String): List<DeviceFactEntity>

    @Query("SELECT * FROM device_facts ORDER BY category ASC, `key` ASC")
    suspend fun all(): List<DeviceFactEntity>

    @Query("UPDATE device_facts SET invalidated = 1 WHERE `key` = :key")
    suspend fun markInvalidatedByKey(key: String)

    @Query("UPDATE device_facts SET invalidated = 1 WHERE category = :category")
    suspend fun markInvalidatedByCategory(category: String)

    @Query("UPDATE device_facts SET invalidated = 1")
    suspend fun markAllInvalidated()

    @Query("DELETE FROM device_facts WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM device_facts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM device_facts")
    suspend fun count(): Int

    /** Facts that must be re-produced: explicitly invalidated or past their TTL. */
    @Query("SELECT * FROM device_facts WHERE invalidated = 1 OR (ttlMs > 0 AND :now - updatedAt > ttlMs)")
    suspend fun expired(now: Long): List<DeviceFactEntity>

    @Insert
    suspend fun insertEvent(entity: DeviceFactEventEntity): Long

    @Query("SELECT * FROM device_fact_events ORDER BY at DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int): List<DeviceFactEventEntity>

    @Query("DELETE FROM device_fact_events")
    suspend fun clearEvents()

    /** Deletes every journal entry older than [cutoff] (epoch millis). */
    @Query("DELETE FROM device_fact_events WHERE at < :cutoff")
    suspend fun deleteEventsOlderThan(cutoff: Long): Int

    /** Keeps only the newest [keep] journal entries, deleting the rest (oldest first). */
    @Query(
        "DELETE FROM device_fact_events WHERE id NOT IN " +
            "(SELECT id FROM device_fact_events ORDER BY at DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneEventsToNewest(keep: Int): Int
}
