package com.andharness.ru.data.saf

import com.andharness.ru.data.db.dao.SafGrantDao
import com.andharness.ru.data.db.entity.SafGrantEntity
import com.andharness.ru.domain.saf.SafGrant
import com.andharness.ru.domain.saf.SafGrantRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafGrantRepositoryImpl @Inject constructor(
    private val dao: SafGrantDao
) : SafGrantRepository {

    override suspend fun add(uri: String, treeDocumentId: String, displayName: String): Long {
        val now = System.currentTimeMillis()
        // lastCheckedAt = 0 means "never verified yet"; lastOk = false until the first check
        // actually confirms the grant is still accessible.
        return dao.insert(
            SafGrantEntity(
                uri = uri,
                treeDocumentId = treeDocumentId,
                displayName = displayName,
                createdAt = now,
                lastCheckedAt = 0,
                lastOk = false
            )
        )
    }

    override suspend fun list(): List<SafGrant> = dao.all().map { it.toDomain() }

    override suspend fun get(id: Long): SafGrant? = dao.byId(id)?.toDomain()

    override suspend fun findByUri(uri: String): SafGrant? = dao.byUri(uri)?.toDomain()

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun markChecked(id: Long, ok: Boolean) =
        dao.markChecked(id, System.currentTimeMillis(), ok)

    private fun SafGrantEntity.toDomain() = SafGrant(
        id = id,
        uri = uri,
        treeDocumentId = treeDocumentId,
        displayName = displayName,
        createdAt = createdAt,
        lastCheckedAt = lastCheckedAt,
        lastOk = lastOk
    )
}
