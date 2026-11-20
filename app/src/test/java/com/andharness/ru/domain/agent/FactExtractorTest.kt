package com.andharness.ru.domain.agent

import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.assist.AssistStats
import com.andharness.ru.domain.assist.MemoryHit
import com.andharness.ru.domain.assist.MemorySaveResult
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A configurable fake client: records calls and replays a fixed event list. */
private class RecordingLlmClient(private val events: List<LlmEvent>) : LlmClient {
    var calls = 0
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): Flow<LlmEvent> = flow {
        calls++
        lastRequest = request
        events.forEach { emit(it) }
    }
}

private class FakeSettingsStore(private val configured: Boolean) : LlmSettingsStore {
    override val config: Flow<LlmSettings> = flowOf(
        LlmSettings(endpoint = if (configured) "https://example.test/v1" else "", model = "m")
    )

    override suspend fun updateEndpoint(value: String) = Unit
    override suspend fun updateModel(value: String) = Unit
    override suspend fun updateReasoningEffort(value: String) = Unit
    override suspend fun updateContextCompressionTokens(value: Long) = Unit
    override suspend fun getApiKey(): String? = null
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun getAuthConfig(): LlmAuthConfig = LlmAuthConfig.DEFAULT
    override suspend fun setAuthConfig(config: LlmAuthConfig) = Unit
    override suspend fun reset() = Unit
    override fun isConfigured(): Flow<Boolean> = flowOf(configured)
}

/** Records every consolidated save so the extractor's tag/importance can be asserted. */
private class RecordingAssistRepository : AssistRepository {
    data class Saved(val text: String, val tags: String, val importance: Int)

    val saved = mutableListOf<Saved>()

    override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> = emptyList()
    override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> = emptyList()
    override suspend fun addNote(kind: String, text: String): Long = 0
    override suspend fun deleteNoteById(id: Long): Boolean = false
    override suspend fun listMemories(limit: Int): List<NoteEntity> = emptyList()
    override suspend fun deleteMemory(id: Long): Boolean = false
    override suspend fun stats(): AssistStats = AssistStats(0, 0, emptyMap())
    override suspend fun saveMemory(text: String, tags: String, importance: Int): Long = 0
    override suspend fun saveMemoryConsolidated(
        text: String,
        tags: String,
        importance: Int
    ): MemorySaveResult {
        saved += Saved(text, tags, importance)
        return MemorySaveResult(saved.size.toLong(), true, text.trim(), tags.trim(), importance)
    }

    override suspend fun searchMemories(query: String, limit: Int): List<NoteEntity> = emptyList()
    override suspend fun searchMemoriesRanked(query: String, limit: Int): List<MemoryHit> = emptyList()
    override suspend fun listTodos(limit: Int): List<TodoEntity> = emptyList()
    override suspend fun addTodo(text: String): Long = 0
    override suspend fun setTodoDone(id: Long, done: Boolean): Boolean = false
    override suspend fun deleteTodo(id: Long): Boolean = false
}

class FactExtractorTest {

    // ---------------------------------------------------------------- pure parser

    @Test
    fun stripsBulletsNumberingWhitespaceAndQuotes() {
        val facts = parseFacts(
            """
            - Любит зелёный чай
            1. Живёт в Москве
            2) Работает в IT
            • Учится на курсах
            * «Ценит тишину»
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "Любит зелёный чай",
                "Живёт в Москве",
                "Работает в IT",
                "Учится на курсах",
                "Ценит тишину"
            ),
            facts
        )
    }

    @Test
    fun dropsEmptyAndPunctuationOnlyLines() {
        val facts = parseFacts("Факт один\n\n   \n-\n---\n.\nФакт два")
        assertEquals(listOf("Факт один", "Факт два"), facts)
    }

    @Test
    fun capsFactCount() {
        val raw = (1..9).joinToString("\n") { "- факт $it" }
        assertEquals(5, parseFacts(raw).size)
        assertEquals(listOf("факт 1", "факт 2"), parseFacts(raw, maxFacts = 2))
    }

    @Test
    fun capsFactLength() {
        val facts = parseFacts("- " + "x".repeat(500), maxChars = 10)
        assertEquals(listOf("x".repeat(10)), facts)
    }

    @Test
    fun dropsCaseInsensitiveDuplicatesKeepingTheFirst() {
        val facts = parseFacts("- Любит чай\n- любит ЧАЙ\n- Любит кофе")
        assertEquals(listOf("Любит чай", "Любит кофе"), facts)
    }

    @Test
    fun blankResponseYieldsNoFacts() {
        assertTrue(parseFacts("").isEmpty())
        assertTrue(parseFacts("   \n  \n").isEmpty())
    }

    // ---------------------------------------------------------------- extractor decisions

    @Test
    fun skipsEntirelyWhenNotConfiguredOrEmpty() = runTest {
        val client = RecordingLlmClient(listOf(LlmEvent.Text("пользователь любит чай"), LlmEvent.Done))
        val repo = RecordingAssistRepository()
        val extractor = FactExtractor(client, FakeSettingsStore(configured = false), repo)

        val message = LlmMessage("user", "я люблю чай")
        assertEquals(0, extractor.extractAndSave(listOf(message)))
        assertEquals("no LLM call may be made when not configured", 0, client.calls)

        val configured = FactExtractor(client, FakeSettingsStore(configured = true), repo)
        assertEquals(0, configured.extractAndSave(emptyList()))
        assertEquals("no LLM call may be made for empty input", 0, client.calls)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun parsesAndSavesFactsWithAutoTagAndImportance() = runTest {
        val client = RecordingLlmClient(
            listOf(
                LlmEvent.Text("- Любит зелёный чай\n2. Живёт в Москве\n"),
                LlmEvent.Done
            )
        )
        val repo = RecordingAssistRepository()
        val extractor = FactExtractor(client, FakeSettingsStore(configured = true), repo)

        val savedCount = extractor.extractAndSave(
            listOf(
                LlmMessage("user", "я люблю зелёный чай"),
                LlmMessage("assistant", "запомнил")
            )
        )

        assertEquals(2, savedCount)
        assertEquals(1, client.calls)
        assertEquals(
            listOf(
                RecordingAssistRepository.Saved("Любит зелёный чай", AUTO_TAG, AUTO_IMPORTANCE),
                RecordingAssistRepository.Saved("Живёт в Москве", AUTO_TAG, AUTO_IMPORTANCE)
            ),
            repo.saved
        )
        assertTrue(
            "the prompt must carry the dropped dialogue",
            client.lastRequest!!.messages.last().content!!.contains("я люблю зелёный чай")
        )
    }

    @Test
    fun llmErrorDoesNotThrowAndSavesNothing() = runTest {
        val client = RecordingLlmClient(listOf(LlmEvent.Error("нет соединения", true)))
        val repo = RecordingAssistRepository()
        val extractor = FactExtractor(client, FakeSettingsStore(configured = true), repo)

        assertEquals(0, extractor.extractAndSave(listOf(LlmMessage("user", "привет"))))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun emptyModelAnswerSavesNothing() = runTest {
        val client = RecordingLlmClient(listOf(LlmEvent.Done))
        val repo = RecordingAssistRepository()
        val extractor = FactExtractor(client, FakeSettingsStore(configured = true), repo)

        assertEquals(0, extractor.extractAndSave(listOf(LlmMessage("assistant", "ок"))))
        assertTrue(repo.saved.isEmpty())
        assertFalse(repo.saved.isNotEmpty())
    }

    @Test
    fun toolAndBlankMessagesAreNotSentToTheModel() = runTest {
        val client = RecordingLlmClient(listOf(LlmEvent.Done))
        val extractor = FactExtractor(client, FakeSettingsStore(configured = true), RecordingAssistRepository())

        extractor.extractAndSave(
            listOf(
                LlmMessage("tool", "сырой вывод инструмента"),
                LlmMessage("user", "   "),
                LlmMessage("assistant", "ответ")
            )
        )

        val prompt = client.lastRequest!!.messages.last().content!!
        assertTrue(prompt.contains("ответ"))
        assertFalse("tool output must not leak into the extraction prompt", prompt.contains("сырой вывод"))
    }
}
