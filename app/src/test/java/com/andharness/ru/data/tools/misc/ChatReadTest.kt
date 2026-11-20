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

private fun chat(id: Long, title: String = "chat-$id", updatedAt: Long = id) =
    ChatEntity(id = id, title = title, createdAt = 0, updatedAt = updatedAt)

private fun msg(id: Long, role: String, content: String, chatId: Long = 1) =
    MessageEntity(id = id, chatId = chatId, role = role, content = content, createdAt = id)

class ChatReadTest {

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

    /** Minimal settings fake: only `isConfigured` matters to the handlers. */
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

    // ---------------------------------------------------------------- limits

    @Test
    fun clampsListSearchAndRecallLimits() {
        assertEquals(DEFAULT_CHAT_LIST_LIMIT, clampChatListLimit(null))
        assertEquals(1, clampChatListLimit(0))
        assertEquals(5, clampChatListLimit(5))
        assertEquals(MAX_CHAT_LIST_LIMIT, clampChatListLimit(999))

        assertEquals(DEFAULT_CHAT_SEARCH_LIMIT, clampChatSearchLimit(null))
        assertEquals(1, clampChatSearchLimit(-3))
        assertEquals(MAX_CHAT_SEARCH_LIMIT, clampChatSearchLimit(999))

        assertEquals(DEFAULT_CHAT_RECALL_LIMIT, clampChatRecallLimit(null))
        assertEquals(1, clampChatRecallLimit(0))
        assertEquals(7, clampChatRecallLimit(7))
        assertEquals(MAX_CHAT_RECALL_LIMIT, clampChatRecallLimit(1_000))
    }

    // ---------------------------------------------------------------- chat_list

    @Test
    fun previewUsesLastNonBlankMessageAndTruncates() {
        val messages = listOf(
            msg(1, "user", "первое"),
            msg(2, "assistant", "   "),
            msg(3, "user", "x".repeat(CHAT_PREVIEW_CHARS + 50)),
        )
        val preview = chatPreview(messages)!!
        assertTrue(preview.startsWith("Пользователь: "))
        assertTrue("long preview must be truncated", preview.endsWith("…"))
        assertNull(chatPreview(listOf(msg(1, "user", "  "))))
        assertNull(chatPreview(emptyList()))
    }

    @Test
    fun listSummariesHonourLimitAndCount() {
        val chats = listOf(chat(3, updatedAt = 300), chat(2, updatedAt = 200), chat(1, updatedAt = 100))
        val byChat = mapOf(
            3L to listOf(msg(1, "user", "hi")),
            2L to listOf(msg(1, "user", "a"), msg(2, "assistant", "b")),
        )
        val rows = buildChatSummaries(chats, byChat, limit = 2)
        assertEquals(listOf(3L, 2L), rows.map { it.id })
        assertEquals(1, rows[0].messageCount)
        assertEquals(2, rows[1].messageCount)
        assertEquals("Пользователь: hi", rows[0].lastPreview)
        assertNull("chat 1 is not selected", rows.find { it.id == 1L })

        val all = buildChatSummaries(chats, byChat, limit = 50)
        assertEquals(3, all.size)
        assertEquals(0, all.last { it.id == 1L }.messageCount)
        assertNull(all.last { it.id == 1L }.lastPreview)
    }

    @Test
    fun formatsListAndEmptyListHonestly() {
        val rows = buildChatSummaries(
            listOf(chat(7, title = "Покупки", updatedAt = 1_700_000_000_000)),
            mapOf(7L to listOf(msg(1, "user", "молоко"))),
            limit = 20,
        )
        val out = formatChatList(rows)
        assertTrue(out.contains("[7]"))
        assertTrue(out.contains("Покупки"))
        assertTrue(out.contains("сообщений: 1"))
        assertTrue(out.contains("Пользователь: молоко"))
        assertEquals("Чатов пока нет.", formatChatList(emptyList()))
    }

    // ---------------------------------------------------------------- chat_search

    @Test
    fun snippetIsBoundedAndCentredOnTheMatch() {
        val long = "a".repeat(200) + "needle" + "b".repeat(200)
        val snippet = snippetAround(long, "needle", maxChars = 40)
        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.length <= 42)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertEquals("короткий", snippetAround("короткий", "нет"))
    }

    @Test
    fun searchMatchesTitlesAndMessageTextWithinBounds() {
        val chats = listOf(
            chat(1, title = "Работа"),
            chat(2, title = "Отпуск"),
            chat(3, title = "Разное"),
        )
        val byChat = mapOf(
            2L to listOf(
                msg(1, "user", "билеты на море", 2),
                msg(2, "assistant", "море тёплое", 2),
                msg(3, "user", "море ещё", 2),
                msg(4, "user", "море снова", 2),
            ),
            3L to listOf(msg(1, "user", "ничего", 3)),
        )
        val byTitle = searchChats(chats, byChat, "работ", limit = 20)
        assertEquals(listOf(1L), byTitle.map { it.id })
        assertTrue(byTitle.single().titleMatch)
        assertTrue(byTitle.single().snippets.isEmpty())

        val byText = searchChats(chats, byChat, "море", limit = 20)
        assertEquals(listOf(2L), byText.map { it.id })
        assertFalse(byText.single().titleMatch)
        assertEquals(
            "snippets per chat must be bounded",
            CHAT_SEARCH_SNIPPETS_PER_CHAT,
            byText.single().snippets.size,
        )

        assertEquals(emptyList<ChatSearchHit>(), searchChats(chats, byChat, "нет-такого", limit = 20))
        assertEquals(emptyList<ChatSearchHit>(), searchChats(chats, byChat, "   ", limit = 20))
    }

    @Test
    fun searchHonoursLimitAcrossChats() {
        val chats = (1L..5L).map { chat(it, title = "тема") }
        val byChat = chats.associate { it.id to emptyList<MessageEntity>() }
        val hits = searchChats(chats, byChat, "тема", limit = 2)
        assertEquals(2, hits.size)
        assertEquals(listOf(1L, 2L), hits.map { it.id })
    }

    @Test
    fun formatsSearchAndNoMatchHonestly() {
        val hits = listOf(
            ChatSearchHit(4, "Отпуск", false, listOf(ChatSearchSnippet("Пользователь", "…море…"))),
        )
        val out = formatChatSearch("море", hits, scannedChats = 10)
        assertTrue(out.contains("[4]"))
        assertTrue(out.contains("Отпуск"))
        assertTrue(out.contains("Пользователь: …море…"))

        val none = formatChatSearch("нет", emptyList(), scannedChats = 10)
        assertTrue(none.contains("ничего не найдено"))
        assertTrue(none.contains("проверено чатов: 10"))
    }

    // ---------------------------------------------------------------- chat_recall selection / prompt

    @Test
    fun recallSelectionKeepsLastMessagesInOrderAndSkipsBlank() {
        val messages = listOf(
            msg(1, "user", "старое"),
            msg(2, "assistant", "  "),
            msg(3, "user", "новое-1"),
            msg(4, "assistant", "новое-2"),
        )
        val selection = selectChatRecallLines(messages, limit = 3)
        assertEquals(listOf("Пользователь: новое-1", "Меха: новое-2"), selection.lines.map { "${it.author}: ${it.text}" })
        assertEquals(1, selection.skippedEmpty)
        assertEquals(0, selection.droppedForLength)
    }

    @Test
    fun recallSelectionDropsOldestOverCharBudget() {
        val messages = listOf(
            msg(1, "user", "0123456789"),
            msg(2, "user", "abcdefghij"),
            msg(3, "user", "ABCDEFGHIJ"),
        )
        val selection = selectChatRecallLines(messages, limit = 10, maxChars = 20)
        assertEquals(listOf("abcdefghij", "ABCDEFGHIJ"), selection.lines.map { it.text })
        assertEquals(1, selection.droppedForLength)
    }

    @Test
    fun recallPromptIsGroundedAndNumbered() {
        val prompt = buildChatRecallPrompt(
            listOf(
                ChatRecallLine("Пользователь", "Поедем в отпуск?"),
                ChatRecallLine("Меха", "Да, в июле."),
            )
        )
        assertTrue(prompt.contains("[1] Пользователь: Поедем в отпуск?"))
        assertTrue(prompt.contains("[2] Меха: Да, в июле."))
        assertTrue(prompt.contains("не выдумывай"))
        assertTrue(CHAT_RECALL_SYSTEM_PROMPT.contains("ТОЛЬКО на переданные сообщения"))
    }

    @Test
    fun formatsRecallWithHonestNotes() {
        val selection = ChatRecallSelection(
            lines = listOf(ChatRecallLine("Пользователь", "x")),
            skippedEmpty = 2,
            droppedForLength = 1,
        )
        val out = formatChatRecall(9, "Планы", selection, "Короткое резюме.")
        assertTrue(out.contains("id 9"))
        assertTrue(out.contains("Планы"))
        assertTrue(out.contains("учтено сообщений: 1"))
        assertTrue(out.contains("Короткое резюме."))
        assertTrue(out.contains("без текста пропущено: 2"))
        assertTrue(out.contains("не поместилось по длине: 1"))
    }

    @Test
    fun emptySelectionMessagesAreHonest() {
        assertEquals(
            "В этом чате нет текстовых сообщений — резюмировать нечего.",
            emptyRecallSelectionMessage(ChatRecallSelection(emptyList(), skippedEmpty = 3, droppedForLength = 0)),
        )
        assertTrue(
            emptyRecallSelectionMessage(
                ChatRecallSelection(emptyList(), skippedEmpty = 1, droppedForLength = 2)
            ).contains("не поместились по длине"),
        )
    }

    // ---------------------------------------------------------------- handler honesty (I/O)

    private fun handlers(
        client: FakeLlmClient,
        repository: FakeChatRepository,
        configured: Boolean = true,
    ): Map<String, com.andharness.ru.domain.tools.ToolHandler> =
        ChatReadToolsHandler(client, FakeSettings(configured), repository)
            .handlers()
            .associateBy { it.name }

    @Test
    fun chatListHandlerMapsChatsAndHonoursLimit() = runTest {
        val repo = FakeChatRepository(
            listOf(chat(3, "C", 300), chat(2, "B", 200), chat(1, "A", 100)),
            mapOf(3L to listOf(msg(1, "user", "последнее", 3))),
        )
        val result = handlers(FakeLlmClient(listOf(LlmEvent.Text("x"))), repo)["chat_list"]!!
            .execute(buildJsonObject { put("limit", "1") })
        assertTrue(result.success)
        assertTrue(result.output.contains("[3]"))
        assertTrue(result.output.contains("последнее"))
        assertTrue("chat 2 and 1 must be cut by limit=1", !result.output.contains("[2]"))
    }

    @Test
    fun chatListHandlerEmptyAccountIsHonest() = runTest {
        val result = handlers(FakeLlmClient(emptyList()), FakeChatRepository())["chat_list"]!!
            .execute(buildJsonObject {})
        assertTrue(result.success)
        assertTrue(result.output.contains("Чатов пока нет"))
    }

    @Test
    fun chatSearchHandlerFindsTextAndReportsNoMatch() = runTest {
        val repo = FakeChatRepository(
            listOf(chat(1, "Работа")),
            mapOf(1L to listOf(msg(1, "user", "обсудили бюджет"))),
        )
        val client = FakeLlmClient(emptyList())
        val found = handlers(client, repo)["chat_search"]!!
            .execute(buildJsonObject { put("query", "бюджет") })
        assertTrue(found.success)
        assertTrue(found.output.contains("[1]"))
        assertTrue(found.output.contains("бюджет"))

        val missing = handlers(client, repo)["chat_search"]!!
            .execute(buildJsonObject { put("query", "единорог") })
        assertTrue(missing.success)
        assertTrue(missing.output.contains("ничего не найдено"))
        assertEquals("search must not call the LLM", 0, client.calls)
    }

    @Test
    fun chatSearchRequiresQuery() = runTest {
        val result = handlers(FakeLlmClient(emptyList()), FakeChatRepository(listOf(chat(1))))["chat_search"]!!
            .execute(buildJsonObject {})
        assertFalse(result.success)
        assertTrue(result.output.contains("query"))
    }

    @Test
    fun chatRecallDefaultsToMostRecentChatInOneCall() = runTest {
        val repo = FakeChatRepository(
            listOf(chat(2, "Новый", 200), chat(1, "Старый", 100)),
            mapOf(2L to listOf(msg(1, "user", "привет", 2), msg(2, "assistant", "привет!", 2))),
        )
        val client = FakeLlmClient(listOf(LlmEvent.Text("Ключевые пункты: приветствие.")))
        val result = handlers(client, repo)["chat_recall"]!!.execute(buildJsonObject {})

        assertTrue(result.output, result.success)
        assertEquals("exactly one LLM call", 1, client.calls)
        assertTrue(result.output.contains("Новый"))
        assertTrue(result.output.contains("Ключевые пункты"))
        assertEquals(CHAT_RECALL_SYSTEM_PROMPT, client.lastRequest!!.messages.first().content)
    }

    @Test
    fun chatRecallHonoursLimitAndOrder() = runTest {
        val messages = (1L..5L).map { msg(it, "user", "сообщение-$it", 1) }
        val repo = FakeChatRepository(listOf(chat(1, "Чат", 100)), mapOf(1L to messages))
        val client = FakeLlmClient(listOf(LlmEvent.Text("резюме")))
        val result = handlers(client, repo)["chat_recall"]!!
            .execute(buildJsonObject { put("limit", "2") })

        assertTrue(result.success)
        val prompt = client.lastRequest!!.messages.last().content!!
        assertTrue(prompt.contains("сообщение-4"))
        assertTrue(prompt.contains("сообщение-5"))
        assertTrue("older messages must be cut by limit", !prompt.contains("сообщение-1"))
    }

    @Test
    fun chatRecallReportsEmptyChatHonestly() = runTest {
        val repo = FakeChatRepository(listOf(chat(1, "Чат", 100)), mapOf(1L to listOf(msg(1, "user", "   ", 1))))
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val result = handlers(client, repo)["chat_recall"]!!.execute(buildJsonObject {})

        assertFalse(result.success)
        assertTrue(result.output.contains("нет текстовых сообщений"))
        assertEquals(0, client.calls)
    }

    @Test
    fun chatRecallReportsMissingChatHonestly() = runTest {
        val repo = FakeChatRepository(listOf(chat(1, "Чат", 100)), mapOf(1L to listOf(msg(1, "user", "привет", 1))))
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val result = handlers(client, repo)["chat_recall"]!!
            .execute(buildJsonObject { put("chat_id", "99") })

        assertFalse(result.success)
        assertTrue(result.output.contains("99"))
        assertEquals(0, client.calls)
    }

    @Test
    fun chatRecallReportsNoChatsAndUnconfiguredModelHonestly() = runTest {
        val emptyRepo = FakeChatRepository()
        val c1 = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val noChats = handlers(c1, emptyRepo)["chat_recall"]!!.execute(buildJsonObject {})
        assertFalse(noChats.success)
        assertTrue(noChats.output.contains("Нет ни одного чата"))
        assertEquals(0, c1.calls)

        val repo = FakeChatRepository(listOf(chat(1, "Чат", 100)), mapOf(1L to listOf(msg(1, "user", "привет", 1))))
        val c2 = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val unconfigured = handlers(c2, repo, configured = false)["chat_recall"]!!
            .execute(buildJsonObject {})
        assertFalse(unconfigured.success)
        assertTrue(unconfigured.output.contains("не настроена"))
        assertEquals(0, c2.calls)
    }

    @Test
    fun chatRecallReportsEmptyModelAnswerHonestly() = runTest {
        val repo = FakeChatRepository(listOf(chat(1, "Чат", 100)), mapOf(1L to listOf(msg(1, "user", "привет", 1))))
        val client = FakeLlmClient(listOf(LlmEvent.Done))
        val result = handlers(client, repo)["chat_recall"]!!.execute(buildJsonObject {})

        assertFalse(result.success)
        assertTrue(result.output.contains("пустой ответ"))
    }

    @Test
    fun chatRecallRejectsNonNumericChatId() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val result = handlers(client, FakeChatRepository())["chat_recall"]!!
            .execute(buildJsonObject { put("chat_id", "abc") })
        assertFalse(result.success)
        assertTrue(result.output.contains("числовым"))
        assertEquals(0, client.calls)
    }
}
