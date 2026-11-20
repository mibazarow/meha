package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.ImportanceCount
import com.andharness.ru.data.db.dao.MemoryEntityDao
import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.MissingEntityLinkMemory
import com.andharness.ru.data.db.dao.MissingLinkMemory
import com.andharness.ru.data.db.dao.NoteFtsDao
import com.andharness.ru.data.db.entity.GenericEntity
import com.andharness.ru.data.db.entity.MemoryEntityLinkEntity
import com.andharness.ru.data.db.entity.MemoryPersonEntity
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.domain.retrieval.MemoryCandidateSelector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GRAPH-расширение recall: память, не совпавшая лексически с запросом, но связанная через
 * `memory ↔ person` с лексически найденным «семенем», попадает в результаты как настоящее
 * совпадение. При `memoryPersonDao = null` граф не читается и поведение прежнее.
 */
class MemoryGraphRecallTest {

    private class FakeAssistDao : AssistDao {
        val notes = mutableListOf<NoteEntity>()
        val touched = mutableListOf<Long>()

        override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }.sortedByDescending { it.id }.take(limit)

        override suspend fun listNotesOldestByUse(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }.take(limit)

        override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind && it.text.contains(query) }.take(limit)

        override suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity> =
            notes.filter { it.kind == kind && it.id in ids }

        override suspend fun insertNote(entity: NoteEntity): Long {
            val id = (notes.maxOfOrNull { it.id } ?: 0L) + 1
            notes += entity.copy(id = id)
            return id
        }

        override suspend fun touchNote(id: Long, timestamp: Long) {
            touched += id
            val index = notes.indexOfFirst { it.id == id }
            if (index >= 0) {
                notes[index] = notes[index].copy(
                    lastUsedAt = timestamp,
                    accessCount = notes[index].accessCount + 1
                )
            }
        }

        override suspend fun updateMemory(
            id: Long,
            text: String,
            tags: String,
            importance: Int,
            lastUsedAt: Long
        ): Int = 0

        override suspend fun deleteNoteById(id: Long, kind: String): Int {
            val removed = notes.removeAll { it.id == id && it.kind == kind }
            return if (removed) 1 else 0
        }

        override suspend fun countByKind(kind: String): Int = notes.count { it.kind == kind }

        override suspend fun countByImportance(kind: String): List<ImportanceCount> =
            notes.filter { it.kind == kind }
                .groupingBy { it.importance }
                .eachCount()
                .map { ImportanceCount(it.key, it.value) }

        override suspend fun listOpinions(limit: Int): List<NoteEntity> =
            notes.filter { it.kind == "opinion" }.sortedByDescending { it.id }.take(limit)

        override suspend fun supersededNoteIds(): List<Long> =
            notes.map { it.supersedesId }.filter { it != 0L }

        override suspend fun listTodos(limit: Int): List<TodoEntity> = emptyList()
        override suspend fun getTodo(id: Long): TodoEntity? = null
        override suspend fun insertTodo(entity: TodoEntity): Long = 0
        override suspend fun setTodoDone(id: Long, done: Boolean) = Unit
        override suspend fun deleteTodo(id: Long): Int = 0
    }

    private class FakeMemoryPersonDao(
        private val byNote: Map<Long, Set<Long>>
    ) : MemoryPersonDao {
        override suspend fun personIdsForNotes(noteIds: List<Long>): List<Long> =
            noteIds.flatMap { byNote[it].orEmpty() }.distinct()

        override suspend fun noteIdsForPerson(personId: Long, limit: Int): List<Long> =
            byNote.filterValues { personId in it }.keys.sorted().take(limit)

        override suspend fun upsertAll(entities: List<MemoryPersonEntity>) = Unit
        override suspend fun countForNote(noteId: Long): Int = 0
        override suspend fun deleteForNote(noteId: Long): Int = 0
        override suspend fun deleteAll() = Unit
        override suspend fun memoriesMissingLinks(limit: Int): List<MissingLinkMemory> = emptyList()
    }

    private class FakeMemoryEntityDao(
        private val byNote: Map<Long, Set<Long>>
    ) : MemoryEntityDao {
        override suspend fun entityIdsForNotes(noteIds: List<Long>): List<Long> =
            noteIds.flatMap { byNote[it].orEmpty() }.distinct()

        override suspend fun noteIdsForEntity(entityId: Long, limit: Int): List<Long> =
            byNote.filterValues { entityId in it }.keys.sorted().take(limit)

        override suspend fun insertAll(entities: List<GenericEntity>): List<Long> = emptyList()
        override suspend fun byNormalizedNames(names: List<String>): List<GenericEntity> = emptyList()
        override suspend fun touchLastSeen(ids: List<Long>, at: Long) = Unit
        override suspend fun upsertLinks(links: List<MemoryEntityLinkEntity>) = Unit
        override suspend fun countForNote(noteId: Long): Int = 0
        override suspend fun deleteForNote(noteId: Long): Int = 0
        override suspend fun memoriesMissingLinks(limit: Int): List<MissingEntityLinkMemory> =
            emptyList()
    }

    private class FakeNoteFtsDao(
        private val candidateIds: List<Long>,
        private val notes: List<NoteEntity>
    ) : NoteFtsDao {
        override suspend fun searchCandidateIds(matchQuery: String, limit: Int): List<Long> =
            candidateIds.take(limit)

        override suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity> =
            notes.filter { it.kind == kind && it.id in ids }
    }

    private fun memory(id: Long, text: String, tags: String = ""): NoteEntity =
        NoteEntity(
            id = id,
            kind = "memory",
            text = text,
            tags = tags,
            importance = 0,
            createdAt = 1_000L
        )

    /**
     * Большая таблица: FTS-шортлист возвращает только «семя» (id 1). Связанная память (id 2)
     * не входит в шортлист и добирается исключительно через граф.
     */
    private fun largeDao(): FakeAssistDao {
        val dao = FakeAssistDao()
        dao.notes += memory(1L, "Рабочая встреча с ФНС по декларации", tags = "налоговая, фнс")
        dao.notes += memory(2L, "Анна любит тёмный шоколад")
        var nextId = 3L
        while (dao.notes.count { it.kind == "memory" } <= MemoryCandidateSelector.FTS_THRESHOLD) {
            dao.notes += memory(nextId, "пустая запись $nextId")
            nextId++
        }
        return dao
    }

    @Test
    fun graphConnectedMemoryIsSurfacedEvenWithoutLexicalMatch() = runTest {
        val dao = largeDao()
        val personDao = FakeMemoryPersonDao(byNote = mapOf(1L to setOf(7L), 2L to setOf(7L)))
        val fts = FakeNoteFtsDao(candidateIds = listOf(1L), notes = dao.notes)
        val repo = AssistRepositoryImpl(dao, fts, memoryPersonDao = personDao)

        val ranked = repo.searchMemoriesRanked("налоговая")

        assertEquals(
            "the graph-connected memory joins the seed",
            setOf(1L, 2L),
            ranked.map { it.note.id }.toSet()
        )
        val graphHit = ranked.first { it.note.id == 2L }
        assertTrue("a graph-reached memory is a genuine match", graphHit.lexicallyMatched)
        assertTrue(ranked.first { it.note.id == 2L }.score > 0.0)

        val injected = repo.searchMemories("налоговая")
        assertTrue(
            "prompt injection surfaces the graph-connected memory",
            injected.any { it.id == 2L }
        )
    }

    @Test
    fun entityConnectedMemoryIsSurfacedEvenWithoutLexicalMatch() = runTest {
        val dao = largeDao()
        val entityDao = FakeMemoryEntityDao(byNote = mapOf(1L to setOf(11L), 2L to setOf(11L)))
        val fts = FakeNoteFtsDao(candidateIds = listOf(1L), notes = dao.notes)
        val repo = AssistRepositoryImpl(dao, fts, memoryEntityDao = entityDao)

        val ranked = repo.searchMemoriesRanked("налоговая")

        assertEquals(
            "the entity-connected memory joins the seed",
            setOf(1L, 2L),
            ranked.map { it.note.id }.toSet()
        )
        val graphHit = ranked.first { it.note.id == 2L }
        assertTrue("an entity-reached memory is a genuine match", graphHit.lexicallyMatched)
        assertTrue(ranked.first { it.note.id == 2L }.score > 0.0)
    }

    @Test
    fun nullMemoryPersonDaoKeepsTheGraphMemoryUnsurfaced() = runTest {
        val dao = largeDao()
        val fts = FakeNoteFtsDao(candidateIds = listOf(1L), notes = dao.notes)
        val repo = AssistRepositoryImpl(dao, fts)

        val ranked = repo.searchMemoriesRanked("налоговая")

        assertEquals("without the graph only the FTS seed is recalled", listOf(1L), ranked.map { it.note.id })
        assertTrue(
            "nothing graph-related is injected",
            repo.searchMemories("налоговая").none { it.id == 2L }
        )
    }
}
