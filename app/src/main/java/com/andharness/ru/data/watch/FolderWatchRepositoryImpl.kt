package com.andharness.ru.data.watch

import com.andharness.ru.data.db.dao.FolderWatchDao
import com.andharness.ru.data.db.entity.FolderWatchEntity
import com.andharness.ru.data.db.entity.FolderWatchEventEntity
import com.andharness.ru.domain.watch.FolderWatch
import com.andharness.ru.domain.watch.FolderWatchEvent
import com.andharness.ru.domain.watch.FolderWatchRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderWatchRepositoryImpl @Inject constructor(
    private val dao: FolderWatchDao
) : FolderWatchRepository {

    override suspend fun addWatch(path: String, mask: Int): Long {
        return dao.insertWatch(
            FolderWatchEntity(
                path = path,
                mask = mask,
                startAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun listWatches(): List<FolderWatch> = dao.allWatches().map { it.toDomain() }

    override suspend fun getWatch(id: Long): FolderWatch? = dao.watchById(id)?.toDomain()

    override suspend fun removeWatch(id: Long) = dao.deleteWatch(id)

    override suspend fun addEvent(watchId: Long, event: Int, name: String): Long {
        return dao.insertEvent(
            FolderWatchEventEntity(
                watchId = watchId,
                event = event,
                name = name,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    override suspend fun events(watchId: Long, limit: Int): List<FolderWatchEvent> =
        dao.eventsFor(watchId, limit).map { it.toDomain() }

    override suspend fun eventCount(watchId: Long): Int = dao.eventCount(watchId)

    override suspend fun clearEvents(watchId: Long) = dao.clearEvents(watchId)

    private fun FolderWatchEntity.toDomain() = FolderWatch(
        id = id,
        path = path,
        mask = mask,
        startAt = startAt
    )

    private fun FolderWatchEventEntity.toDomain() = FolderWatchEvent(
        id = id,
        watchId = watchId,
        event = event,
        name = name,
        timestamp = timestamp
    )
}
