package com.andharness.ru.data.trash

import com.andharness.ru.data.db.dao.TrashEntryDao
import com.andharness.ru.data.db.entity.TrashEntryEntity
import com.andharness.ru.domain.trash.TrashEntry
import com.andharness.ru.domain.trash.TrashRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashRepositoryImpl @Inject constructor(
    private val dao: TrashEntryDao
) : TrashRepository {

    override suspend fun add(
        originalPath: String,
        trashPath: String,
        name: String,
        isDirectory: Boolean,
        sizeBytes: Long
    ): Long {
        return dao.insert(
            TrashEntryEntity(
                originalPath = originalPath,
                trashPath = trashPath,
                name = name,
                isDirectory = isDirectory,
                sizeBytes = sizeBytes,
                deletedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun list(): List<TrashEntry> = dao.all().map { it.toDomain() }

    override suspend fun get(id: Long): TrashEntry? = dao.byId(id)?.toDomain()

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun clear() = dao.clear()

    private fun TrashEntryEntity.toDomain() = TrashEntry(
        id = id,
        originalPath = originalPath,
        trashPath = trashPath,
        name = name,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        deletedAt = deletedAt
    )
}
