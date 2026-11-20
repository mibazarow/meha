package com.andharness.ru.domain.trash

/**
 * Persistence for app-managed trash entries. Implemented on top of the Room `trash_entries`
 * table; the schema change that introduced it ships with an explicit migration (no destructive
 * fallback), see
 * https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
interface TrashRepository {
    /** Records a trashed item and returns its row id. */
    suspend fun add(
        originalPath: String,
        trashPath: String,
        name: String,
        isDirectory: Boolean,
        sizeBytes: Long
    ): Long

    /** All trash entries, newest first. */
    suspend fun list(): List<TrashEntry>

    suspend fun get(id: Long): TrashEntry?

    /** Removes a single entry (after restore or permanent delete). */
    suspend fun remove(id: Long)

    /** Removes all entries (empties the trash). */
    suspend fun clear()
}
