package com.andharness.ru.data.tools.assist

import com.andharness.ru.data.assist.AssistRepositoryImpl
import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.ImportanceCount
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `opinion_set` / `opinion_list` end to end on top of the Room-backed repository (fake DAO):
 * a saved opinion keeps its stance confidence, a newer opinion supersedes an older one without
 * deleting it, and the superseded one is hidden from the list and from recall unless asked for.
 */
class AssistToolsHandlerOpinionTest {

    private class FakeAssistDao : AssistDao {
        val notes = mutableListOf<NoteEntity>()
        private var nextId = 1L

        override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }.sortedByDescending { it.id }.take(limit)

        override suspend fun listNotesOldestByUse(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }.take(limit)

        override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind && it.text.contains(query) }.take(limit)

        override suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity> =
            notes.filter { it.kind == kind && it.id in ids }

        override suspend fun listOpinions(limit: Int): List<NoteEntity> =
            notes.filter { it.kind == "opinion" }.sortedByDescending { it.id }.take(limit)

        override suspend fun supersededNoteIds(): List<Long> =
            notes.map { it.supersedesId }.filter { it != 0L }

        override suspend fun insertNote(entity: NoteEntity): Long {
            val id = nextId++
            notes += entity.copy(id = id)
            return id
        }

        override suspend fun touchNote(id: Long, timestamp: Long) = Unit

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

        override suspend fun listTodos(limit: Int): List<TodoEntity> = emptyList()
        override suspend fun getTodo(id: Long): TodoEntity? = null
        override suspend fun insertTodo(entity: TodoEntity): Long = 0
        override suspend fun setTodoDone(id: Long, done: Boolean) = Unit
        override suspend fun deleteTodo(id: Long): Int = 0
    }

    private fun handler(repository: AssistRepository, name: String): ToolHandler =
        AssistToolsHandler(repository).handlers().first { it.name == name }

    @Test
    fun opinionSetStoresTextTagsAndConfidence() = runTest {
        val repo = AssistRepositoryImpl(FakeAssistDao())
        val set = handler(repo, "opinion_set")

        val result = set.execute(
            buildJsonObject {
                put("text", "Кофе лучше чая")
                put("tags", "напитки")
                put("confidence", "0.6")
            }
        )

        assertTrue("opinion_set must succeed: ${result.output}", result.success)
        val saved = repo.listOpinions()
        assertEquals(1, saved.size)
        assertEquals("opinion", saved.first().kind)
        assertEquals("Кофе лучше чая", saved.first().text)
        assertEquals("напитки", saved.first().tags)
        assertEquals(0.6f, saved.first().confidence, 1e-4f)
    }

    @Test
    fun opinionSetWithoutTextFailsHonestly() = runTest {
        val repo = AssistRepositoryImpl(FakeAssistDao())
        val set = handler(repo, "opinion_set")

        val result = set.execute(buildJsonObject {})

        assertFalse(result.success)
        assertTrue(repo.listOpinions().isEmpty())
    }

    @Test
    fun supersededOpinionIsHiddenByDefaultAndShownOnRequest() = runTest {
        val repo = AssistRepositoryImpl(FakeAssistDao())
        val set = handler(repo, "opinion_set")
        val list = handler(repo, "opinion_list")

        set.execute(buildJsonObject { put("text", "Люблю шоколад"); put("confidence", "0.8") })
        val firstId = repo.listOpinions().first().id
        set.execute(
            buildJsonObject {
                put("text", "Люблю шоколад")
                put("confidence", "0.8")
                put("supersedesId", firstId.toString())
            }
        )
        val secondId = repo.listOpinions().first().id

        val defaultOutput = list.execute(buildJsonObject {}).output
        assertTrue("the new opinion must be listed", defaultOutput.contains("#$secondId"))
        assertFalse("the retired opinion must be hidden by default", defaultOutput.contains("#$firstId"))

        val withRetired = list.execute(buildJsonObject { put("includeRetired", "true") }).output
        assertTrue("includeRetired must show the old opinion", withRetired.contains("#$firstId"))
        assertTrue("the old opinion must be marked retired", withRetired.contains("вытеснено"))
    }

    @Test
    fun retiredOpinionIsExcludedFromRecall() = runTest {
        val repo = AssistRepositoryImpl(FakeAssistDao())
        val set = handler(repo, "opinion_set")

        set.execute(buildJsonObject { put("text", "Люблю шоколад") })
        val firstId = repo.listOpinions().first().id
        set.execute(
            buildJsonObject {
                put("text", "Люблю шоколад")
                put("supersedesId", firstId.toString())
            }
        )

        val recalled = repo.searchMemoriesRanked("шоколад").map { it.note.id }

        assertFalse("a retired opinion must not rank", recalled.contains(firstId))
        assertTrue("the current opinion must still be recalled", recalled.isNotEmpty())
    }

    @Test
    fun emptyOpinionListReportsHonestly() = runTest {
        val repo = AssistRepositoryImpl(FakeAssistDao())
        val list = handler(repo, "opinion_list")

        val result = list.execute(buildJsonObject {})

        assertTrue(result.success)
        assertEquals("Мнений нет.", result.output)
    }
}
