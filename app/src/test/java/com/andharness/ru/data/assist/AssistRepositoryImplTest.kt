package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.ImportanceCount
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * update.md idea #3 end-to-end repository ranking: with the same fixtures, a differently-worded
 * query returns the semantically matching memory first, and the prompt auto-injection
 * ([AssistRepositoryImpl.searchMemories]) never picks up the honest non-lexical fallback.
 */
class AssistRepositoryImplTest {

    private class FakeAssistDao : AssistDao {
        val notes = mutableListOf<NoteEntity>()
        val touched = mutableListOf<Long>()
        private var nextId = 1L

        override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }.sortedByDescending { it.id }.take(limit)

        override suspend fun listNotesOldestByUse(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }
                .sortedWith(
                    compareBy({ if (it.lastUsedAt > 0L) it.lastUsedAt else it.createdAt }, { it.id })
                )
                .take(limit)

        override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind && it.text.contains(query) }.take(limit)

        override suspend fun listNotesByIds(kind: String, ids: List<Long>): List<NoteEntity> =
            notes.filter { it.kind == kind && it.id in ids }

        override suspend fun insertNote(entity: NoteEntity): Long {
            val id = nextId++
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
        ): Int {
            val index = notes.indexOfFirst { it.id == id && it.kind == "memory" }
            if (index < 0) return 0
            notes[index] = notes[index].copy(
                text = text,
                tags = tags,
                importance = importance,
                lastUsedAt = lastUsedAt
            )
            return 1
        }

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

    private suspend fun seed(dao: FakeAssistDao): AssistRepositoryImpl {
        val repo = AssistRepositoryImpl(dao)
        repo.saveMemory("Пользователь любит бирюзовый цвет", tags = "цвет, предпочтение", importance = 0)
        repo.saveMemory(
            "Рабочая встреча с ФНС по декларации",
            tags = "налоговая, фнс, декларация",
            importance = 1
        )
        return repo
    }

    @Test
    fun differentlyWordedQueryRanksTheTaxMemoryFirst() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        val hits = repo.searchMemoriesRanked("как я решил вопрос с налоговой")

        assertEquals("only the tax memory is a lexical match", 1, hits.size)
        assertEquals("the ФНС memory must rank first", "Рабочая встреча с ФНС по декларации", hits.first().note.text)
        assertTrue("relevance score must be positive", hits.first().score > 0.0)
        assertTrue(hits.first().lexicallyMatched)
    }

    @Test
    fun colourQueryRanksTheColourMemoryFirst() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        val hits = repo.searchMemoriesRanked("бирюзовый")

        assertEquals("Пользователь любит бирюзовый цвет", hits.first().note.text)
        assertTrue("an unrelated memory must not be a lexical hit", hits.none { it.note.text.contains("ФНС") })
    }

    @Test
    fun promptInjectionReturnsOnlyLexicalMatches() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        assertTrue("an unrelated turn injects nothing", repo.searchMemories("прогноз погоды в токио").isEmpty())
        assertEquals(1, repo.searchMemories("налоговая").size)
    }

    @Test
    fun lexicalRecallTouchesLastUsedAtOnlyForRealMatches() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val taxId = dao.notes.first { it.text.contains("ФНС") }.id
        val colourId = dao.notes.first { it.text.contains("бирюзовый") }.id

        repo.searchMemoriesRanked("налоговая")

        assertTrue("the matched memory must be touched", taxId in dao.touched)
        assertTrue("an unmatched memory must not be touched", colourId !in dao.touched)
        assertTrue(dao.notes.first { it.id == taxId }.lastUsedAt > 0L)
    }

    @Test
    fun emptyTableReturnsNothing() = runTest {
        val repo = AssistRepositoryImpl(FakeAssistDao())
        assertTrue(repo.searchMemoriesRanked("налоговая").isEmpty())
    }

    @Test
    fun listMemoriesIsNewestFirstAndBounded() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        val all = repo.listMemories()

        assertEquals("newest memory first", "Рабочая встреча с ФНС по декларации", all.first().text)
        assertEquals(2, all.size)
        assertEquals("limit bounds the output", 1, repo.listMemories(limit = 1).size)
        assertTrue("notes are excluded from the memory list", all.all { it.kind == "memory" })
    }

    @Test
    fun gcScanReturnsOldestByDecayReferenceFirst() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val colourId = dao.notes.first { it.text.contains("бирюзовый") }.id

        val scanned = repo.listMemoriesForGc()

        assertEquals("the oldest-decaying memory is scanned first", colourId, scanned.first().id)
        assertEquals(2, scanned.size)
    }

    @Test
    fun deleteMemoryIsScopedToMemoryRows() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val noteId = repo.addNote("note", "обычная заметка")
        val memoryId = dao.notes.first { it.kind == "memory" }.id

        assertTrue("a memory id must be deletable", repo.deleteMemory(memoryId))
        assertTrue("the memory row must be gone", dao.notes.none { it.id == memoryId })
        assertFalse("memory_delete must not touch a note id", repo.deleteMemory(noteId))
        assertTrue("the note must survive", dao.notes.any { it.id == noteId })
        assertFalse("a second delete reports not found", repo.deleteMemory(memoryId))
    }

    @Test
    fun deleteNoteByIdIsScopedToNoteRows() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val noteId = repo.addNote("note", "обычная заметка")
        val memoryId = dao.notes.first { it.kind == "memory" }.id

        assertFalse("note_delete must not delete a memory id", repo.deleteNoteById(memoryId))
        assertTrue("the memory must survive", dao.notes.any { it.id == memoryId })
        assertTrue(repo.deleteNoteById(noteId))
        assertTrue("the note must be gone", dao.notes.none { it.id == noteId })
    }

    @Test
    fun statsCountNotesAndMemoryByImportance() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        repo.addNote("note", "заметка 1")
        repo.addNote("note", "заметка 2")

        val stats = repo.stats()

        assertEquals(2, stats.noteCount)
        assertEquals(2, stats.memoryCount)
        assertEquals(mapOf(0 to 1, 1 to 1), stats.memoryByImportance)
    }

    @Test
    fun statsOnEmptyTableAreZero() = runTest {
        val stats = AssistRepositoryImpl(FakeAssistDao()).stats()

        assertEquals(0, stats.noteCount)
        assertEquals(0, stats.memoryCount)
        assertTrue(stats.memoryByImportance.isEmpty())
    }

    @Test
    fun consolidatedSaveInsertsWhenNothingMatches() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        val saved = repo.saveMemoryConsolidated("Пользователь любит кофе", tags = "кофе", importance = 0)

        assertTrue("an unrelated memory must be inserted", saved.created)
        assertEquals(3, dao.notes.count { it.kind == "memory" })
        assertEquals(saved.id, dao.notes.first { it.id == saved.id }.id)
    }

    @Test
    fun consolidatedSaveUpdatesNearDuplicateInsteadOfInserting() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val original = dao.notes.first { it.text.contains("бирюзовый") }

        val saved = repo.saveMemoryConsolidated(
            "Пользователь любит бирюзовый цвет",
            tags = "любимый, цвет",
            importance = 0
        )

        assertFalse("a near-duplicate must update, not insert", saved.created)
        assertEquals("the existing row id is reported", original.id, saved.id)
        assertEquals("no duplicate row is added", 2, dao.notes.count { it.kind == "memory" })
        val merged = dao.notes.first { it.id == original.id }
        assertEquals("the NEW text wins", "Пользователь любит бирюзовый цвет", merged.text)
        assertEquals("tags are unioned", "цвет, предпочтение, любимый", merged.tags)
        assertTrue("lastUsedAt is touched", merged.lastUsedAt > 0L)
    }

    @Test
    fun consolidatedSaveTakesMaxImportanceFromBothSides() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val important = dao.notes.first { it.text.contains("ФНС") }

        val saved = repo.saveMemoryConsolidated(
            "Рабочая встреча с ФНС по декларации",
            tags = "налоговая, фнс",
            importance = 0
        )

        assertFalse(saved.created)
        assertEquals(important.id, saved.id)
        assertEquals("importance must never drop", 1, dao.notes.first { it.id == important.id }.importance)
    }

    /** BUGS.md M1: a filler never reaches the table, and the caller is told so honestly. */
    @Test
    fun junkIsRejectedInsteadOfStored() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val before = dao.notes.size

        val saved = repo.saveMemoryConsolidated("ок", tags = "", importance = 0)

        assertTrue("the caller must be told the fact was rejected", saved.rejected)
        assertEquals("nothing may be written", before, dao.notes.size)
        assertEquals(0L, saved.id)
    }

    @Test
    fun plainSaveAlsoRejectsJunk() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)
        val before = dao.notes.size

        assertEquals(0L, repo.saveMemory("спасибо", tags = "", importance = 1))
        assertEquals(before, dao.notes.size)
    }

    /** A real fact still goes through untouched. */
    @Test
    fun aRealFactIsStillStored() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        val saved = repo.saveMemoryConsolidated("Любит крепкий чай без сахара", importance = 1)

        assertFalse(saved.rejected)
        assertTrue(saved.created)
        assertTrue(dao.notes.any { it.text == "Любит крепкий чай без сахара" })
    }

    /**
     * BUGS.md M5: an empty result reads as «я ничего не помню» and invites the model to invent.
     * With memories present but no lexical match, recall returns the freshest/most important ones,
     * labelled as non-matches, instead of nothing.
     */
    @Test
    fun anUnrelatedQueryReturnsFreshMemoriesInsteadOfNothing() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        val hits = repo.searchMemoriesRanked("прогноз погоды в токио")

        assertTrue("must not be empty while memories exist", hits.isNotEmpty())
        assertTrue("and must be honest about it", hits.none { it.lexicallyMatched })
    }

    /** The prompt-injection path stays strict: it never injects a non-match. */
    @Test
    fun promptInjectionStaysEmptyForAnUnrelatedQuery() = runTest {
        val dao = FakeAssistDao()
        val repo = seed(dao)

        assertTrue(repo.searchMemories("прогноз погоды в токио").isEmpty())
    }

    /**
     * BUGS.md M2, end-to-end: the repository must pass the «is this a past question» decision from
     * RecallIntent into the ranker, so an old agreement is not buried under fresh trivia.
     */
    @Test
    fun aPastQuestionReachesTheOldAgreement() = runTest {
        val dao = FakeAssistDao()
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        dao.notes += NoteEntity(
            id = 1L, kind = "memory", text = "Договорились с Мариной про аренду офиса",
            tags = "марина, аренда", createdAt = now - 400 * day
        )
        dao.notes += NoteEntity(
            id = 2L, kind = "memory", text = "Договорились с Мариной про аренду офиса",
            tags = "марина, аренда", createdAt = now
        )
        val repo = AssistRepositoryImpl(dao)

        // Normal query first: a lexical recall touches lastUsedAt, so the past run must come
        // second or both rows would look equally fresh for an unrelated reason.
        val normal = repo.searchMemoriesRanked("аренда офиса Марина", limit = 2)
        val past = repo.searchMemoriesRanked("что мы решили по аренде офиса с Мариной", limit = 2)

        assertEquals("both twins must come back", 2, past.size)
        assertEquals(
            "a past question neutralises the age prior: identical relevance scores identically",
            past.first { it.note.id == 1L }.score,
            past.first { it.note.id == 2L }.score,
            1e-9
        )
        assertTrue(
            "without the past-question signal the fresh twin still wins on the age prior",
            normal.first { it.note.id == 2L }.score > normal.first { it.note.id == 1L }.score
        )
    }
}
