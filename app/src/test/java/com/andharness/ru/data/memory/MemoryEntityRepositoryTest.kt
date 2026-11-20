package com.andharness.ru.data.memory

import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.dao.MissingEntityLinkMemory
import com.andharness.ru.data.db.entity.GenericEntity
import com.andharness.ru.data.db.entity.MemoryEntityLinkEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryEntityRepository] behaviour against an in-memory DAO fake: the pure
 * [com.andharness.ru.domain.retrieval.MemoryEntityExtractor] is exercised end-to-end through the
 * persistence layer without Room or an Android runtime.
 */
class MemoryEntityRepositoryTest {

    private class FakeMemoryEntityDao : MemoryEntityDao {
        val nodes = mutableListOf<GenericEntity>()
        val links = mutableListOf<MemoryEntityLinkEntity>()
        val missing = mutableListOf<MissingEntityLinkMemory>()
        var lastMissingLimit: Int = -1
        var insertCalls: Int = 0
        var upsertLinkCalls: Int = 0

        override suspend fun insertAll(entities: List<GenericEntity>): List<Long> {
            insertCalls++
            return entities.map { entity ->
                val existing = nodes.firstOrNull { it.normalizedName == entity.normalizedName }
                if (existing != null) {
                    -1L
                } else {
                    val id = (nodes.maxOfOrNull { it.id } ?: 0L) + 1
                    nodes += entity.copy(id = id)
                    id
                }
            }
        }

        override suspend fun byNormalizedNames(names: List<String>): List<GenericEntity> =
            nodes.filter { it.normalizedName in names }

        override suspend fun touchLastSeen(ids: List<Long>, at: Long) {
            ids.forEach { id ->
                val index = nodes.indexOfFirst { it.id == id }
                if (index >= 0) nodes[index] = nodes[index].copy(lastSeenAt = at)
            }
        }

        override suspend fun upsertLinks(links: List<MemoryEntityLinkEntity>) {
            upsertLinkCalls++
            for (link in links) {
                this.links.removeAll { it.noteId == link.noteId && it.entityId == link.entityId }
                this.links += link
            }
        }

        override suspend fun entityIdsForNotes(noteIds: List<Long>): List<Long> =
            links.filter { it.noteId in noteIds }.map { it.entityId }.distinct()

        override suspend fun noteIdsForEntity(entityId: Long, limit: Int): List<Long> =
            links.filter { it.entityId == entityId }
                .sortedByDescending { it.weight }
                .map { it.noteId }
                .take(limit)

        override suspend fun countForNote(noteId: Long): Int = links.count { it.noteId == noteId }

        override suspend fun deleteForNote(noteId: Long): Int {
            val removed = links.count { it.noteId == noteId }
            links.removeAll { it.noteId == noteId }
            return removed
        }

        override suspend fun memoriesMissingLinks(limit: Int): List<MissingEntityLinkMemory> {
            lastMissingLimit = limit
            return missing.take(limit)
        }
    }

    @Test
    fun linkMemoryUpsertsNodesAndWeightedLinks() = runTest {
        val dao = FakeMemoryEntityDao()
        val repo = MemoryEntityRepository(dao)

        val written = repo.linkMemory(
            noteId = 42L,
            text = "Вчера мы запустили Годот Энджин в продакшн",
            tags = "налоговая, фнс"
        )

        assertEquals("two tags plus one proper noun", 3, written)
        assertEquals(3, dao.nodes.size)
        assertEquals(
            setOf("налоговая", "фнс", "годот энджин"),
            dao.nodes.map { it.normalizedName }.toSet()
        )
        assertEquals(3, dao.links.size)
        assertTrue("every link belongs to the memory", dao.links.all { it.noteId == 42L })
        assertTrue("every weight must be positive", dao.links.all { it.weight > 0f })
        assertEquals("nodes and links written in one pass", 1, dao.insertCalls)
        assertEquals(1, dao.upsertLinkCalls)
    }

    @Test
    fun linkMemoryReusesExistingNodesAcrossCalls() = runTest {
        val dao = FakeMemoryEntityDao()
        val repo = MemoryEntityRepository(dao)

        repo.linkMemory(1L, "Вчера запустили Годот Энджин", "налоговая")
        repo.linkMemory(2L, "Опять запустили Годот Энджин", "налоговая")

        assertEquals("no duplicate nodes", 2, dao.nodes.size)
        assertEquals("each memory links to both entities", 4, dao.links.size)
        assertEquals(
            "both memories reference the same two node ids",
            setOf(1L, 2L),
            dao.links.map { it.entityId }.toSet()
        )
        assertEquals(2, dao.insertCalls)
    }

    @Test
    fun linkMissingLinksOnlyReportedMemoriesAndRespectsLimit() = runTest {
        val dao = FakeMemoryEntityDao().apply {
            missing += MissingEntityLinkMemory(1L, "Вчера запустили Годот Энджин", "")
            missing += MissingEntityLinkMemory(2L, "просто заметка без имён", "")
        }
        val repo = MemoryEntityRepository(dao)

        val linked = repo.linkMissing(limit = 2)

        assertEquals("only the memory with an entity is linked", 1, linked)
        assertEquals("the DAO must receive the passed limit", 2, dao.lastMissingLimit)
        assertEquals(1, dao.links.size)
        assertEquals(1L, dao.links.single().noteId)
    }

    @Test
    fun linkMemoryRejectsBlankInputsAndNonPositiveId() = runTest {
        val dao = FakeMemoryEntityDao()
        val repo = MemoryEntityRepository(dao)

        assertEquals(0, repo.linkMemory(0L, "Годот Энджин", ""))
        assertEquals(0, repo.linkMemory(-1L, "Годот Энджин", ""))
        assertEquals(0, repo.linkMemory(42L, "   ", "  "))
        assertEquals(0, dao.insertCalls)
        assertTrue(dao.nodes.isEmpty())
        assertTrue(dao.links.isEmpty())
    }
}
