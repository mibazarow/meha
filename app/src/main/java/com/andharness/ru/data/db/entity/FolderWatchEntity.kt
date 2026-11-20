package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted folder watch (TZ item B4). The watch itself is a process-lifetime
 * `android.os.FileObserver`; this row keeps its metadata so it survives a restart.
 */
@Entity(tableName = "folder_watches")
data class FolderWatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Absolute path inside the tool root that is being watched. */
    val path: String,
    /** FileObserver event mask used when the watch was started. */
    val mask: Int,
    /** Epoch millis when the watch was created. */
    val startAt: Long
)
