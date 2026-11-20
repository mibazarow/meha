package com.andharness.ru.data.memory

import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.MissingLinkMemory
import com.andharness.ru.data.db.dao.PersonAliasDao
import com.andharness.ru.data.db.dao.PersonDao
import com.andharness.ru.data.db.entity.MemoryPersonEntity
import com.andharness.ru.data.db.entity.PersonAliasEntity
import com.andharness.ru.data.db.entity.PersonEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryPersonRepository] behaviour against in-memory DAO fakes: the pure [MemoryPersonLinker] is
 * exercised end-to-end through the persistence layer without Room or an Android runtime.
 */
class MemoryPersonRepositoryTest {

    private class FakePersonDao : PersonDao {
        val persons = mutableListOf<PersonEntity>()
        var lastAllLimit: Int = -1

        override suspend fun insert(entity: PersonEntity): Long {
            val id = if (entity.id > 0L) entity.id else (persons.maxOfOrNull { it.id } ?: 0L) + 1
            persons += entity.copy(id = id)
            return id
        }

        override suspend fun byId(id: Long): PersonEntity? = persons.firstOrNull { it.id == id }

        override suspend fun searchByName(query: String, limit: Int): List<PersonEntity> =
            persons.filter { it.displayName.contains(query, ignoreCase = true) }.take(limit)

        override suspend fun byIds(ids: List<Long>): List<PersonEntity> =
            persons.filter { it.id in ids }

        override suspend fun recent(limit: Int): List<PersonEntity> =
            persons.sortedByDescending { it.updatedAt }.take(limit)

        override suspend fun all(limit: Int): List<PersonEntity> {
            lastAllLimit = limit
            return persons.sortedBy { it.id }.take(limit)
        }

        override suspend fun updateFields(
            id: Long,
            displayName: String,
            notes: String,
            updatedAt: Long
        ): Int = 0

        override suspend fun delete(id: Long): Int =
            if (persons.removeAll { it.id == id }) 1 else 0

        override suspend fun count(): Int = persons.size
    }

    private class FakePersonAliasDao : PersonAliasDao {
        val aliases = mutableListOf<PersonAliasEntity>()
        var lastAllLimit: Int = -1

        override suspend fun insert(entity: PersonAliasEntity): Long {
            val duplicate = aliases.any { it.personId == entity.personId && it.alias == entity.alias }
            if (duplicate) return -1L
            val id = if (entity.id > 0L) entity.id else (aliases.maxOfOrNull { it.id } ?: 0L) + 1
            aliases += entity.copy(id = id)
            return id
        }

        override suspend fun forPerson(personId: Long): List<PersonAliasEntity> =
            aliases.filter { it.personId == personId }.sortedBy { it.alias.lowercase() }

        override suspend fun all(limit: Int): List<PersonAliasEntity> {
            lastAllLimit = limit
            return aliases
                .sortedWith(compareBy({ it.personId }, { it.alias.lowercase() }))
                .take(limit)
        }

        override suspend fun byAlias(alias: String): List<PersonAliasEntity> =
            aliases.filter { it.alias.equals(alias, ignoreCase = true) }

        override suspend fun delete(personId: Long, alias: String): Int =
            if (aliases.removeAll { it.personId == personId && it.alias.equals(alias, ignoreCase = true) }) 1 else 0

        override suspend fun reassign(fromId: Long, intoId: Long): Int = 0

        override suspend fun deleteForPerson(personId: Long): Int {
            val removed = aliases.count { it.personId == personId }
            aliases.removeAll { it.personId == personId }
            return removed
        }
    }

    private class FakeMemoryPersonDao : MemoryPersonDao {
        val rows = mutableListOf<MemoryPersonEntity>()
        val missing = mutableListOf<MissingLinkMemory>()
        var upsertCalls: Int = 0
        var lastMissingLimit: Int = -1

        override suspend fun upsertAll(entities: List<MemoryPersonEntity>) {
            upsertCalls++
            for (entity in entities) {
                rows.removeAll { it.noteId == entity.noteId && it.personId == entity.personId }
                rows += entity
            }
        }

        override suspend fun personIdsForNotes(noteIds: List<Long>): List<Long> =
            rows.filter { it.noteId in noteIds }.map { it.personId }.distinct()

        override suspend fun noteIdsForPerson(personId: Long, limit: Int): List<Long> =
            rows.filter { it.personId == personId }
                .sortedByDescending { it.weight }
                .map { it.noteId }
                .take(limit)

        override suspend fun countForNote(noteId: Long): Int = rows.count { it.noteId == noteId }

        override suspend fun deleteForNote(noteId: Long): Int =
            if (rows.removeAll { it.noteId == noteId }) 1 else 0

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun memoriesMissingLinks(limit: Int): List<MissingLinkMemory> {
            lastMissingLimit = limit
            return missing.take(limit)
        }
    }

    private fun person(id: Long, name: String): PersonEntity =
        PersonEntity(id = id, displayName = name, notes = "", createdAt = 0L, updatedAt = 0L)

    private fun alias(id: Long, personId: Long, value: String): PersonAliasEntity =
        PersonAliasEntity(id = id, personId = personId, alias = value)

    @Test
    fun linkMemoryWritesOneRowPerMatchedPersonWithPositiveWeight() = runTest {
        val personDao = FakePersonDao().apply {
            persons += person(7L, "Серёга")
            persons += person(9L, "Маша")
        }
        val aliasDao = FakePersonAliasDao().apply { aliases += alias(1L, 7L, "Серега") }
        val memoryPersonDao = FakeMemoryPersonDao()
        val repo = MemoryPersonRepository(personDao, aliasDao, memoryPersonDao)

        val written = repo.linkMemory(42L, "Говорил с Серёгой и Машей")

        assertEquals("one link per matched person", 2, written)
        assertEquals(2, memoryPersonDao.rows.size)
        assertTrue(
            "both matched persons must be linked",
            memoryPersonDao.rows.map { it.personId }.toSet() == setOf(7L, 9L)
        )
        assertTrue(
            "every row belongs to the linked memory",
            memoryPersonDao.rows.all { it.noteId == 42L }
        )
        assertTrue(
            "every weight must be positive",
            memoryPersonDao.rows.all { it.weight > 0f }
        )
        assertEquals(1, memoryPersonDao.upsertCalls)
    }

    @Test
    fun linkMemoryWithUnrelatedTextWritesNothing() = runTest {
        val personDao = FakePersonDao().apply { persons += person(7L, "Серёга") }
        val memoryPersonDao = FakeMemoryPersonDao()
        val repo = MemoryPersonRepository(personDao, FakePersonAliasDao(), memoryPersonDao)

        val written = repo.linkMemory(42L, "Купил молоко и хлеб")

        assertEquals(0, written)
        assertTrue("no upsert for an unrelated memory", memoryPersonDao.rows.isEmpty())
        assertEquals(0, memoryPersonDao.upsertCalls)
    }

    @Test
    fun linkMemoryRejectsBlankTextAndNonPositiveId() = runTest {
        val personDao = FakePersonDao().apply { persons += person(7L, "Серёга") }
        val memoryPersonDao = FakeMemoryPersonDao()
        val repo = MemoryPersonRepository(personDao, FakePersonAliasDao(), memoryPersonDao)

        assertEquals(0, repo.linkMemory(0L, "Говорил с Серёгой"))
        assertEquals(0, repo.linkMemory(-3L, "Говорил с Серёгой"))
        assertEquals(0, repo.linkMemory(42L, "   "))
        assertEquals(0, memoryPersonDao.upsertCalls)
        assertTrue(memoryPersonDao.rows.isEmpty())
    }

    @Test
    fun linkMemoryWithNoPersonsWritesNothing() = runTest {
        val memoryPersonDao = FakeMemoryPersonDao()
        val repo = MemoryPersonRepository(FakePersonDao(), FakePersonAliasDao(), memoryPersonDao)

        val written = repo.linkMemory(42L, "Говорил с Серёгой")

        assertEquals(0, written)
        assertEquals(0, memoryPersonDao.upsertCalls)
        assertTrue(memoryPersonDao.rows.isEmpty())
    }

    @Test
    fun linkMissingLinksOnlyReportedMemoriesAndRespectsLimit() = runTest {
        val personDao = FakePersonDao().apply { persons += person(7L, "Серёга") }
        val memoryPersonDao = FakeMemoryPersonDao().apply {
            missing += MissingLinkMemory(1L, "Говорил с Серёгой")
            missing += MissingLinkMemory(2L, "Купил молоко")
        }
        val repo = MemoryPersonRepository(personDao, FakePersonAliasDao(), memoryPersonDao)

        val linked = repo.linkMissing(limit = 2)

        assertEquals("only the mentioning memory is linked", 1, linked)
        assertEquals("the DAO must receive the passed limit", 2, memoryPersonDao.lastMissingLimit)
        assertEquals(1, memoryPersonDao.rows.size)
        assertEquals(1L, memoryPersonDao.rows.single().noteId)
        assertEquals(7L, memoryPersonDao.rows.single().personId)
    }
}
