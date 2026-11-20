package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.FolderWatchEntity
import com.andharness.ru.data.db.entity.FolderWatchEventEntity

/** Room access to persisted folder watches and their events (tables `folder_watches`, `folder_watch_events`). */
@Dao
interface FolderWatchDao {
    @Insert
    suspend fun insertWatch(entity: FolderWatchEntity): Long

    @Query("SELECT * FROM folder_watches ORDER BY startAt DESC")
    suspend fun allWatches(): List<FolderWatchEntity>

    @Query("SELECT * FROM folder_watches WHERE id = :id LIMIT 1")
    suspend fun watchById(id: Long): FolderWatchEntity?

    @Query("DELETE FROM folder_watches WHERE id = :id")
    suspend fun deleteWatch(id: Long)

    @Insert
    suspend fun insertEvent(entity: FolderWatchEventEntity): Long

    @Query("SELECT * FROM folder_watch_events WHERE watchId = :watchId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun eventsFor(watchId: Long, limit: Int): List<FolderWatchEventEntity>

    @Query("SELECT COUNT(*) FROM folder_watch_events WHERE watchId = :watchId")
    suspend fun eventCount(watchId: Long): Int

    @Query("DELETE FROM folder_watch_events WHERE watchId = :watchId")
    suspend fun clearEvents(watchId: Long)
}
