package com.andharness.ru.data.index

import com.andharness.ru.data.db.dao.DeviceIndexSourceRow
import com.andharness.ru.data.db.dao.DeviceIndexVectorDao
import com.andharness.ru.data.db.entity.DeviceIndexVectorEntity
import com.andharness.ru.domain.retrieval.VectorCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DeviceIndexRepository] against an in-memory [FakeDeviceIndexVectorDao]. The real
 * Room DAO needs instrumentation, so the repository's mapping (enum <-> `kind` string, float vector
 * <-> BLOB via [VectorCodec], skip rules) is exercised through the DAO interface here.
 */
class DeviceIndexRepositoryTest {

    @Test
    fun upsertUnitsNormalisesEncodesAndCounts() = runTest {
        val dao = FakeDeviceIndexVectorDao()
        val repository = DeviceIndexRepository(dao)

        val written = repository.upsertUnits(
            listOf(unit(vector = floatArrayOf(3f, 4f))),
            now = 123L,
        )

        assertEquals(1, written)
        val row = dao.rows.values.single()
        assertEquals(DeviceIndexKind.IMAGE.name, row.kind)
        assertEquals(2, row.dim)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), VectorCodec.decode(row.vector), 1e-6f)
        assertEquals("embeddinggemma-2-q8_0", row.modelId)
        assertEquals(123L, row.updatedAt)
    }

    @Test
    fun upsertUnitsSkipsBlankSourceAndEmptyVector() = runTest {
        val repository = DeviceIndexRepository(FakeDeviceIndexVectorDao())

        val written = repository.upsertUnits(
            listOf(
                unit(sourceUri = "   "),
                unit(vector = FloatArray(0)),
            )
        )

        assertEquals(0, written)
    }

    @Test
    fun listBySourceDecodesVectorAndOrdersChunks() = runTest {
        val dao = FakeDeviceIndexVectorDao()
        val repository = DeviceIndexRepository(dao)
        repository.upsertUnits(
            listOf(
                unit(chunkIndex = 1, text = "second", vector = floatArrayOf(0f, 1f)),
                unit(chunkIndex = 0, text = "first", vector = floatArrayOf(1f, 0f)),
            )
        )

        val stored = repository.listBySource("content://src")

        assertEquals(listOf(0, 1), stored.map { it.chunkIndex })
        assertEquals("first", stored.first().text)
        assertArrayEquals(floatArrayOf(1f, 0f), stored.first().vector, 1e-6f)
        assertEquals(DeviceIndexKind.IMAGE, stored.first().kind)
    }

    @Test
    fun sourceMetadataIsKeyedBySourceUri() = runTest {
        val repository = DeviceIndexRepository(FakeDeviceIndexVectorDao())
        repository.upsertUnits(
            listOf(
                unit(sourceUri = "content://a", lastModified = 10L, sizeBytes = 100L, chunkIndex = 0),
                unit(sourceUri = "content://a", lastModified = 10L, sizeBytes = 100L, chunkIndex = 1),
                unit(sourceUri = "content://b", lastModified = 20L, sizeBytes = 200L),
            )
        )

        val meta = repository.sourceMetadata()

        assertEquals(setOf("content://a", "content://b"), meta.keys)
        assertEquals(DeviceIndexSourceMeta(lastModified = 10L, sizeBytes = 100L), meta.getValue("content://a"))
        assertEquals(DeviceIndexSourceMeta(lastModified = 20L, sizeBytes = 200L), meta.getValue("content://b"))
    }

    @Test
    fun countByKindUsesTheStoredKindName() = runTest {
        val dao = FakeDeviceIndexVectorDao()
        val repository = DeviceIndexRepository(dao)
        repository.upsertUnits(
            listOf(
                unit(kind = DeviceIndexKind.IMAGE, sourceUri = "content://img"),
                unit(kind = DeviceIndexKind.DOC_CHUNK, sourceUri = "content://doc"),
            )
        )

        assertEquals(2, repository.countAll())
        assertEquals(1, repository.countByKind(DeviceIndexKind.IMAGE))
        assertEquals(1, repository.countByKind(DeviceIndexKind.DOC_CHUNK))
    }

    @Test
    fun unknownKindFallsBackToDocChunk() = runTest {
        val dao = FakeDeviceIndexVectorDao()
        val repository = DeviceIndexRepository(dao)
        repository.upsertUnits(listOf(unit()))
        val id = dao.rows.values.single().id
        dao.rows[dao.rows.keys.single()] = dao.rows.values.single().copy(id = id, kind = "FUTURE_KIND")

        val stored = repository.getAll(limit = 10)

        assertEquals(DeviceIndexKind.DOC_CHUNK, stored.single().kind)
    }

    @Test
    fun deleteSourceAndClearDelegateToTheDao() = runTest {
        val dao = FakeDeviceIndexVectorDao()
        val repository = DeviceIndexRepository(dao)
        repository.upsertUnits(listOf(unit()))

        repository.deleteSource("content://src")
        assertNull(dao.rows.keys.firstOrNull { it.first == "content://src" })
        assertEquals("content://src", dao.lastDeletedSource)

        repository.clear()
        assertTrue(dao.rows.isEmpty())
        assertEquals(1, dao.deleteAllCalls)
    }

    private fun unit(
        kind: DeviceIndexKind = DeviceIndexKind.IMAGE,
        sourceUri: String = "content://src",
        chunkIndex: Int = 0,
        text: String = "",
        vector: FloatArray = floatArrayOf(1f, 0f),
        lastModified: Long = 0L,
        sizeBytes: Long = 0L,
    ) = DeviceIndexUnit(
        kind = kind,
        sourceUri = sourceUri,
        displayName = "name",
        mimeType = "image/jpeg",
        sizeBytes = sizeBytes,
        lastModified = lastModified,
        chunkIndex = chunkIndex,
        text = text,
        vector = vector,
        modelId = "embeddinggemma-2-q8_0",
    )

    /** In-memory stand-in for the Room DAO; the repository only depends on this interface. */
    private class FakeDeviceIndexVectorDao : DeviceIndexVectorDao {
        val rows = LinkedHashMap<Pair<String, Int>, DeviceIndexVectorEntity>()
        var deleteAllCalls = 0
        var lastDeletedSource: String? = null
        private var nextId = 1L

        override suspend fun upsert(entities: List<DeviceIndexVectorEntity>) {
            entities.forEach { entity ->
                val key = entity.sourceUri to entity.chunkIndex
                val id = rows[key]?.id ?: nextId++
                rows[key] = entity.copy(id = id)
            }
        }

        override suspend fun deleteBySource(sourceUri: String) {
            lastDeletedSource = sourceUri
            rows.keys.removeAll { it.first == sourceUri }
        }

        override suspend fun listBySource(sourceUri: String): List<DeviceIndexVectorEntity> =
            rows.values.filter { it.sourceUri == sourceUri }.sortedBy { it.chunkIndex }

        override suspend fun countAll(): Int = rows.size

        override suspend fun countByKind(kind: String): Int = rows.values.count { it.kind == kind }

        override suspend fun countByKindAndModel(kind: String, modelId: String): Int =
            rows.values.count { it.kind == kind && it.modelId == modelId }

        override suspend fun deleteByKind(kind: String) {
            rows.keys.removeAll { rows[it]?.kind == kind }
        }

        override suspend fun iterateBatch(limit: Int, offset: Int): List<DeviceIndexVectorEntity> =
            rows.values.sortedBy { it.id }.drop(offset).take(limit)

        override suspend fun getAll(limit: Int): List<DeviceIndexVectorEntity> =
            rows.values.sortedBy { it.id }.take(limit)

        override suspend fun sourceMetadata(): List<DeviceIndexSourceRow> =
            rows.values.groupBy { it.sourceUri }.map { (uri, list) ->
                DeviceIndexSourceRow(
                    sourceUri = uri,
                    lastModified = list.maxOf { it.lastModified },
                    sizeBytes = list.maxOf { it.sizeBytes },
                )
            }

        override suspend fun sourceMetadataByKind(kind: String): List<DeviceIndexSourceRow> =
            rows.values.filter { it.kind == kind }.groupBy { it.sourceUri }.map { (uri, list) ->
                DeviceIndexSourceRow(
                    sourceUri = uri,
                    lastModified = list.maxOf { it.lastModified },
                    sizeBytes = list.maxOf { it.sizeBytes },
                )
            }

        override suspend fun deleteAll() {
            deleteAllCalls++
            rows.clear()
        }
    }
}
