package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single persisted FileObserver event belonging to a [FolderWatchEntity]
 * (table `folder_watch_events`).
 */
@Entity(
    tableName = "folder_watch_events",
    indices = [Index(value = ["watchId"])]
)
data class FolderWatchEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Owning watch id ([FolderWatchEntity.id]). */
    val watchId: Long,
    /** Raw `FileObserver` event constant. */
    val event: Int,
    /** Name of the affected file/folder, relative to the watched directory. */
    val name: String,
    /** Epoch millis when the event was observed. */
    val timestamp: Long
)
