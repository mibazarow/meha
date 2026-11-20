package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted app-managed trash entry (TZ optional item `files_trash*`). The app moves a deleted
 * file/folder into its own trash directory and records it here so it can be listed and restored.
 */
@Entity(tableName = "trash_entries")
data class TrashEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Root-relative original path, used to restore the item. */
    val originalPath: String,
    /** Root-relative path inside the trash directory where the item now lives. */
    val trashPath: String,
    /** Display name of the trashed item. */
    val name: String,
    val isDirectory: Boolean,
    /** Size in bytes at trash time (recursive sum for directories; may be 0 if unknown). */
    val sizeBytes: Long,
    /** Epoch millis when the item was trashed. */
    val deletedAt: Long
)
