package com.andharness.ru.domain.watch

/**
 * Persistence for folder watches and their events. Implemented on top of the Room
 * `folder_watches` / `folder_watch_events` tables; the schema change that introduced them ships
 * with an explicit migration (no destructive fallback), see
 * https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
interface FolderWatchRepository {
    /** Stores a new watch and returns its row id. */
    suspend fun addWatch(path: String, mask: Int): Long

    /** All watches, newest first. */
    suspend fun listWatches(): List<FolderWatch>

    suspend fun getWatch(id: Long): FolderWatch?

    suspend fun removeWatch(id: Long)

    /** Records one observed event and returns its row id. */
    suspend fun addEvent(watchId: Long, event: Int, name: String): Long

    /** The most recent events of a watch, newest first, capped by [limit]. */
    suspend fun events(watchId: Long, limit: Int): List<FolderWatchEvent>

    /** Number of recorded events for a watch. */
    suspend fun eventCount(watchId: Long): Int

    /** Removes all recorded events of a watch. */
    suspend fun clearEvents(watchId: Long)
}
