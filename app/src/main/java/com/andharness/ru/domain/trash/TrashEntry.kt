package com.andharness.ru.domain.trash

/**
 * An app-managed trash entry (TZ optional item `files_trash*`).
 *
 * @param originalPath root-relative original path, used to restore the item.
 * @param trashPath root-relative path inside the trash directory.
 * @param name display name of the trashed item.
 * @param sizeBytes size in bytes at trash time (may be 0 if unknown).
 * @param deletedAt epoch millis when the item was trashed.
 */
data class TrashEntry(
    val id: Long,
    val originalPath: String,
    val trashPath: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val deletedAt: Long
)
