package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.ImportanceCount
import com.andharness.ru.data.db.dao.NoteFtsDao
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.domain.retrieval.MemoryCandidateSelector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поведение отбора кандидатов памяти: на малой таблице FTS не вызывается вовсе
 * (прежний полный скан и прежнее ранжирование), на большой — ранжируются только FTS-кандидаты, а
 * пустой/сбойный FTS-результат откатывается на полный скан.
 */
class MemoryFtsFallbackTest {

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

    private class FakeNoteFtsDao(
        private val candidateIds: List<Long>,
        private val notes: List<NoteEntity>,
        private val throwOnSearch: Boolean = false
    ) : NoteFtsDao {
        var searchCalls = 0
        var lastMatchQuery: String? = null

        override suspend fun searchCandidateIds(matchQuery: String, limit: Int): List<Long> {
            searchCalls++
            lastMatchQuery = matchQuery
            if (throwOnSearch) error("FTS unavailable")
            return candidateIds.take(limit)
        }

        override suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity> =
            notes.filter { it.kind == kind && it.id in ids }
    }

    private fun memory(id: Long, text: String, tags: String = ""): NoteEntity =
        NoteEntity(id = id, kind = "memory", text = text, tags = tags, importance = 0, createdAt = 1_000L)

    private fun largeDaoWith(extra: List<NoteEntity>): FakeAssistDao {
        val dao = FakeAssistDao()
        dao.notes += memory(1L, "Рабочая встреча с ФНС по декларации", tags = "налоговая, фнс")
        dao.notes += extra
        var nextId = 3L
        while (dao.notes.count { it.kind == "memory" } < MemoryCandidateSelector.FTS_THRESHOLD + 1) {
            dao.notes += memory(nextId, "пустая запись $nextId")
            nextId++
        }
        return dao
    }

    @Test
    fun smallTableNeverCallsFtsAndRanksTheSameWay() = runTest {
        val dao = FakeAssistDao()
        dao.notes += memory(1L, "Пользователь любит бирюзовый цвет", tags = "цвет")
        dao.notes += memory(2L, "Рабочая встреча с ФНС по декларации", tags = "налоговая, фнс")
        val fts = FakeNoteFtsDao(candidateIds = listOf(999L), notes = dao.notes)
        val repo = AssistRepositoryImpl(dao, fts)

        val hits = repo.searchMemoriesRanked("налоговая")

        assertEquals("FTS must not be consulted below the threshold", 0, fts.searchCalls)
        assertEquals(1, hits.size)
        assertEquals("Рабочая встреча с ФНС по декларации", hits.first().note.text)
    }

    @Test
    fun largeTableRanksOnlyTheFtsCandidates() = runTest {
        val otherTax = memory(2L, "Ещё одна встреча с ФНС", tags = "налоговая")
        val dao = largeDaoWith(listOf(otherTax))
        val fts = FakeNoteFtsDao(candidateIds = listOf(1L), notes = dao.notes)
        val repo = AssistRepositoryImpl(dao, fts)

        val hits = repo.searchMemoriesRanked("налоговая")

        assertEquals(1, fts.searchCalls)
        assertEquals("only the FTS candidate is ranked", listOf(1L), hits.map { it.note.id })
        assertTrue(fts.lastMatchQuery!!.isNotBlank())
    }

    @Test
    fun emptyFtsResultFallsBackToTheFullScan() = runTest {
        val otherTax = memory(2L, "Ещё одна встреча с ФНС", tags = "налоговая")
        val dao = largeDaoWith(listOf(otherTax))
        val fts = FakeNoteFtsDao(candidateIds = emptyList(), notes = dao.notes)
        val repo = AssistRepositoryImpl(dao, fts)

        val hits = repo.searchMemoriesRanked("налоговая")

        assertEquals(1, fts.searchCalls)
        assertEquals(
            "the fallback must still recall every lexical match",
            setOf(1L, 2L),
            hits.map { it.note.id }.toSet()
        )
    }

    @Test
    fun failingFtsFallsBackToTheFullScan() = runTest {
        val dao = largeDaoWith(emptyList())
        val fts = FakeNoteFtsDao(candidateIds = emptyList(), notes = dao.notes, throwOnSearch = true)
        val repo = AssistRepositoryImpl(dao, fts)

        val hits = repo.searchMemoriesRanked("налоговая")

        assertEquals(1, fts.searchCalls)
        assertEquals(listOf(1L), hits.map { it.note.id })
    }
}
