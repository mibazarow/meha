package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun chat(id: Long, updatedAt: Long = id) =
    ChatEntity(id = id, title = "chat-$id", createdAt = 0, updatedAt = updatedAt)

private fun msg(id: Long, role: String, content: String, chatId: Long = 1) =
    MessageEntity(id = id, chatId = chatId, role = role, content = content, createdAt = id)

class TranslateChatTest {

    /** Fake LLM that records the request and replays a fixed event list (no network). */
    private class FakeLlmClient(private val events: List<LlmEvent>) : LlmClient {
        var calls = 0
        var lastRequest: LlmRequest? = null

        override fun complete(request: LlmRequest): Flow<LlmEvent> = flow {
            calls++
            lastRequest = request
            events.forEach { emit(it) }
        }
    }

    /** Minimal settings fake: only `isConfigured` matters to the handler. */
    private class FakeSettings(private val configured: Boolean) : LlmSettingsStore {
        override val config: Flow<LlmSettings> = flowOf(LlmSettings(endpoint = "", model = "m"))

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

    /** In-memory chat store: chats are returned in the given (most-recent-first) order. */
    private class FakeChatRepository(
        private val chats: List<ChatEntity> = emptyList(),
        private val messages: Map<Long, List<MessageEntity>> = emptyMap(),
    ) : ChatRepository {
        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(chats)
        override fun observeChat(chatId: Long): Flow<ChatEntity?> =
            flowOf(chats.firstOrNull { it.id == chatId })

        override suspend fun createChat(title: String): Long = 0
        override suspend fun chatExists(chatId: Long): Boolean =
            chats.any { it.id == chatId }
        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> =
            flowOf(messages[chatId].orEmpty())

        override suspend fun addMessage(
            chatId: Long,
            role: String,
            content: String,
            toolName: String?,
        ): Long = 0

        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) = Unit
        override suspend fun clearStaleStreaming(): Int = 0
        override suspend fun deleteChat(chatId: Long) = Unit
        override suspend fun renameChat(chatId: Long, title: String) = Unit
        override suspend fun updateSummary(chatId: Long, summary: String) = Unit
        override suspend fun updateContextState(chatId: Long, state: String?) = Unit
        override suspend fun pruneToLimit(keep: Int) = Unit
        override suspend fun deleteAllChats() = Unit
        override suspend fun setPinned(chatId: Long, pinned: Boolean) = Unit
        override suspend fun clearMessages(chatId: Long) = Unit
        override suspend fun mostRecentChatId(): Long? = chats.firstOrNull()?.id
    }

    // ---------------------------------------------------------------- limit / ordering

    @Test
    fun clampsRequestedLimitToDefaultAndCap() {
        assertEquals(DEFAULT_CHAT_TRANSLATE_LIMIT, clampChatLimit(null))
        assertEquals(DEFAULT_CHAT_TRANSLATE_LIMIT, clampChatLimit(20))
        assertEquals(1, clampChatLimit(0))
        assertEquals(1, clampChatLimit(-5))
        assertEquals(5, clampChatLimit(5))
        assertEquals(MAX_CHAT_TRANSLATE_LIMIT, clampChatLimit(MAX_CHAT_TRANSLATE_LIMIT))
        assertEquals(MAX_CHAT_TRANSLATE_LIMIT, clampChatLimit(500))
    }

    @Test
    fun lastMessagesKeepOriginalChronologicalOrder() {
        assertEquals(listOf(3, 4), lastMessagesInOrder(listOf(1, 2, 3, 4), 2))
        assertEquals(listOf(1, 2), lastMessagesInOrder(listOf(1, 2), 5))
        assertEquals(emptyList<Int>(), lastMessagesInOrder(emptyList<Int>(), 3))
    }

    @Test
    fun authorLabelsAreHumanReadable() {
        assertEquals("Пользователь", chatAuthorLabel("user"))
        assertEquals("Меха", chatAuthorLabel("assistant"))
        assertEquals("Система", chatAuthorLabel("system"))
        assertEquals("Инструмент", chatAuthorLabel("tool"))
        assertEquals("custom", chatAuthorLabel("custom"))
        assertEquals("Сообщение", chatAuthorLabel("   "))
    }

    @Test
    fun selectionSkipsEmptyMessagesAndKeepsOrder() {
        val selection = selectChatLines(
            listOf(
                TranslateChatLine("Пользователь", ""),
                TranslateChatLine("Меха", "a"),
                TranslateChatLine("Пользователь", "  "),
                TranslateChatLine("Меха", "b"),
                TranslateChatLine("Пользователь", "c"),
            ),
            limit = 10,
        )
        assertEquals(listOf("a", "b", "c"), selection.lines.map { it.text })
        assertEquals(2, selection.skippedEmpty)
        assertEquals(0, selection.droppedForLength)
    }

    @Test
    fun selectionHonoursLimitBeforeSkipping() {
        val selection = selectChatLines(
            listOf(
                TranslateChatLine("Пользователь", "old"),
                TranslateChatLine("Меха", "b"),
                TranslateChatLine("Пользователь", "c"),
            ),
            limit = 2,
        )
        assertEquals(listOf("b", "c"), selection.lines.map { it.text })
        assertEquals(0, selection.skippedEmpty)
    }

    @Test
    fun selectionDropsOldestMessagesOverTheCharBudget() {
        val selection = selectChatLines(
            listOf(
                TranslateChatLine("Пользователь", "0123456789"), // 10 chars
                TranslateChatLine("Меха", "abcdefghij"),         // 10 chars
                TranslateChatLine("Пользователь", "ABCDEFGHIJ"), // 10 chars
            ),
            limit = 10,
            maxChars = 20,
        )
        assertEquals(listOf("abcdefghij", "ABCDEFGHIJ"), selection.lines.map { it.text })
        assertEquals(1, selection.droppedForLength)
    }

    // ---------------------------------------------------------------- prompt

    @Test
    fun promptNumbersMessagesWithoutAuthorNames() {
        val prompt = buildChatTranslatePrompt(
            listOf(
                TranslateChatLine("Пользователь", "Привет"),
                TranslateChatLine("Меха", "Как дела?"),
            ),
            to = "en",
            from = null,
        )
        assertTrue(prompt.contains("Целевой язык: en"))
        assertTrue(prompt.contains("определи автоматически"))
        assertTrue(prompt.contains("[1] Привет"))
        assertTrue(prompt.contains("[2] Как дела?"))
        assertTrue("author names must not be sent to the model", !prompt.contains("Пользователь"))
    }

    @Test
    fun promptCarriesExplicitSourceAndFlattensMultilineMessages() {
        val prompt = buildChatTranslatePrompt(
            listOf(TranslateChatLine("Меха", "line1\nline2")),
            to = "de",
            from = "ru",
        )
        assertTrue(prompt.contains("Исходный язык: ru"))
        assertTrue(prompt.contains("[1] line1 line2"))
    }

    // ---------------------------------------------------------------- response parsing

    @Test
    fun parsesNumberedTranslations() {
        assertEquals(
            listOf("Hallo", "Tschüss"),
            parseChatTranslations("[1] Hallo\n[2] Tschüss", expected = 2),
        )
        assertEquals(
            listOf("Hallo", "Tschüss"),
            parseChatTranslations("1. Hallo\n2) Tschüss", expected = 2),
        )
    }

    @Test
    fun rejectsIncompleteOrEmptyAnswers() {
        assertNull(parseChatTranslations("[1] only one", expected = 2))
        assertNull(parseChatTranslations("", expected = 2))
        assertNull(parseChatTranslations("no numbering here", expected = 1))
        assertNull(parseChatTranslations("[1] x", expected = 0))
    }

    @Test
    fun ignoresOutOfRangeAndDuplicateNumbers() {
        assertEquals(
            listOf("a", "b"),
            parseChatTranslations("[3] x\n[1] a\n[1] dup\n[2] b", expected = 2),
        )
    }

    // ---------------------------------------------------------------- formatting

    @Test
    fun formatsPerMessageListWithHonestNotes() {
        val selection = TranslateChatSelection(
            lines = listOf(TranslateChatLine("Пользователь", "Привет")),
            skippedEmpty = 1,
            droppedForLength = 2,
        )
        val output = formatChatTranslation(selection, listOf("Hello"), mergedTranslation = "", to = "en")
        assertTrue(output.contains("[1] Пользователь: Hello"))
        assertTrue(output.contains("пропущено сообщений без текста: 1"))
        assertTrue(output.contains("не поместилось по длине: 2"))
    }

    @Test
    fun fallsBackToMergedTranslationWhenAlignmentFails() {
        val selection = TranslateChatSelection(
            lines = listOf(TranslateChatLine("Меха", "Привет")),
            skippedEmpty = 0,
            droppedForLength = 0,
        )
        val output = formatChatTranslation(selection, parsed = null, mergedTranslation = "Hi", to = "en")
        assertTrue(output.contains("один блок"))
        assertTrue(output.contains("Hi"))
        assertTrue("no notes when nothing was skipped", !output.contains("пропущено"))
    }

    // ---------------------------------------------------------------- handler honesty

    private fun handler(
        client: FakeLlmClient,
        repository: FakeChatRepository,
        configured: Boolean = true,
    ) = TranslateChatToolsHandler(client, FakeSettings(configured), repository).handlers().single()

    @Test
    fun rejectsUnknownTargetLanguageWithoutCallingLlm() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val result = handler(client, FakeChatRepository(listOf(chat(1)))).execute(
            buildJsonObject { put("to", "klingon") }
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("klingon"))
        assertEquals("unknown language must not reach the LLM", 0, client.calls)
    }

    @Test
    fun reportsHonestlyWhenThereAreNoChats() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val result = handler(client, FakeChatRepository()).execute(
            buildJsonObject { put("to", "en") }
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("Нет ни одного чата"))
        assertEquals(0, client.calls)
    }

    @Test
    fun reportsHonestlyWhenRequestedChatIsMissing() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val repo = FakeChatRepository(listOf(chat(1)), mapOf(1L to listOf(msg(1, "user", "Привет"))))
        val result = handler(client, repo).execute(
            buildJsonObject { put("to", "en"); put("chat_id", "99") }
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("99"))
        assertEquals(0, client.calls)
    }

    @Test
    fun reportsHonestlyWhenChatHasNoTranslatableText() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val repo = FakeChatRepository(
            listOf(chat(1)),
            mapOf(1L to listOf(msg(1, "user", ""), msg(2, "assistant", "   "))),
        )
        val result = handler(client, repo).execute(buildJsonObject { put("to", "en") })
        assertFalse(result.success)
        assertTrue(result.output.contains("нет текста"))
        assertEquals(0, client.calls)
    }

    @Test
    fun reportsHonestlyWhenModelIsNotConfigured() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val repo = FakeChatRepository(listOf(chat(1)), mapOf(1L to listOf(msg(1, "user", "Привет"))))
        val result = handler(client, repo, configured = false).execute(buildJsonObject { put("to", "en") })
        assertFalse(result.success)
        assertTrue(result.output.contains("не настроена"))
        assertEquals(0, client.calls)
    }

    @Test
    fun reportsEmptyModelAnswerHonestly() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Done))
        val repo = FakeChatRepository(listOf(chat(1)), mapOf(1L to listOf(msg(1, "user", "Привет"))))
        val result = handler(client, repo).execute(buildJsonObject { put("to", "en") })
        assertFalse(result.success)
        assertTrue(result.output.contains("пустой ответ"))
    }

    @Test
    fun defaultsToMostRecentChatAndTranslatesInOneCall() = runTest {
        // observeChats is ordered most-recent-first: chat 2 is newer than chat 1.
        val repo = FakeChatRepository(
            listOf(chat(2, updatedAt = 200), chat(1, updatedAt = 100)),
            mapOf(2L to listOf(msg(1, "user", "Привет"), msg(2, "assistant", "Привет!"))),
        )
        val client = FakeLlmClient(listOf(LlmEvent.Text("[1] Hello\n[2] Hello!")))
        val result = handler(client, repo).execute(buildJsonObject { put("to", "en") })

        assertTrue(result.output, result.success)
        assertEquals("exactly one LLM call for the whole chat", 1, client.calls)
        assertTrue(result.output.contains("[1] Пользователь: Hello"))
        assertTrue(result.output.contains("[2] Меха: Hello!"))
        assertTrue(client.lastRequest!!.messages.last().content!!.contains("Целевой язык: en"))
    }

    @Test
    fun mergesWhenTheModelAnswerIsNotNumbered() = runTest {
        val repo = FakeChatRepository(
            listOf(chat(1)),
            mapOf(1L to listOf(msg(1, "user", "Привет"), msg(2, "assistant", "Привет!"))),
        )
        val client = FakeLlmClient(listOf(LlmEvent.Text("Hello\nHello!")))
        val result = handler(client, repo).execute(buildJsonObject { put("to", "en") })

        assertTrue(result.success)
        assertTrue(result.output.contains("один блок"))
        assertTrue(result.output.contains("Hello"))
    }
}
