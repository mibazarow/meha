package com.andharness.ru.data.agent

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.tools.Tool
import com.andharness.ru.domain.tools.ToolRegistry
import com.andharness.ru.domain.tools.ToolRetriever
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool selection: BM25/query variation with the always-on core set, plus the localized (RU/EN) tool
 * `description` schemas selected by the active language.
 */
class RunToolSelectorTest {

    private fun llmTool(name: String, description: String = ""): LlmTool =
        LlmTool(name = name, description = description, parameters = JsonObject(emptyMap()))

    /** Every CORE tool plus two non-core tools with distinctive vocabulary. */
    private val catalog: List<LlmTool> = buildList {
        ToolRetriever.CORE_TOOLS.forEach { add(llmTool(it)) }
        add(llmTool("sms_send", "отправить смс сообщение абоненту"))
        add(llmTool("rag_query", "поиск по проиндексированным документам"))
    }

    private class FakeRegistry(
        private val defs: List<LlmTool>,
        private val enDefs: List<LlmTool> = defs
    ) : ToolRegistry {
        override fun definitions(): List<LlmTool> = defs
        override fun definitions(language: String): List<LlmTool> =
            if (AppLanguage.normalize(language) == AppLanguage.ENGLISH) enDefs else defs
        override suspend fun execute(name: String, argumentsJson: String): ToolResult =
            ToolResult(true, "")
    }

    private class FakeMcp : McpDynamicTools {
        override suspend fun toolDefinitions(): List<Tool> = emptyList()
        override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult? = null
        override fun invalidate(serverId: String?) = Unit
        override fun seed(server: McpServer, tools: List<McpToolInfo>) = Unit
    }

    /** Minimal chat repository: no messages, so the retrieval query is just the user text. */
    private class FakeRepo : ChatRepository {
        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(emptyList())
        override fun observeChat(chatId: Long): Flow<ChatEntity?> = flowOf(null)
        override suspend fun createChat(title: String): Long = 0
        override suspend fun chatExists(chatId: Long): Boolean = true
        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun addMessage(chatId: Long, role: String, content: String, toolName: String?): Long = 0
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
        override suspend fun mostRecentChatId(): Long? = null
    }

    @Test
    fun cloudModeStillVariesByQuery() = runTest {
        val selector = RunToolSelector(FakeRegistry(catalog), FakeRepo(), FakeMcp())

        val sms = selector.resolve(1, "отправь смс маме").map { it.name }.toSet()
        val rag = selector.resolve(1, "поиск по документам rag_query")
            .map { it.name }.toSet()

        assertTrue("cloud retrieval must surface the sms tool for an sms query", "sms_send" in sms)
        assertTrue("cloud retrieval must surface the rag tool for a rag query", "rag_query" in rag)
        assertNotEquals("cloud selection must vary with the query", sms, rag)
    }

    /**
     * The ACTIVE language ([RunToolSelector.resolve]'s `language`) selects the localized tool schemas:
     * an EN user gets the EN `description` strings (same names/order), an RU user — and the default,
     * language-less call — stays byte-identical to the RU catalog.
     */
    @Test
    fun languageSelectsTheLocalizedToolSchemas() = runTest {
        val enCatalog = catalog.map { it.copy(description = "EN:${it.description}") }
        val selector = RunToolSelector(FakeRegistry(catalog, enCatalog), FakeRepo(), FakeMcp())

        val ruTools = selector.resolve(1, "q", language = "ru")
        val enTools = selector.resolve(1, "q", language = "en")

        assertEquals(
            "the tool set/order must be language-independent (only descriptions differ)",
            ruTools.map { it.name },
            enTools.map { it.name }
        )
        assertTrue("EN user must get the EN descriptions", enTools.all { it.description.startsWith("EN:") })
        assertTrue("RU user must keep the RU descriptions", ruTools.none { it.description.startsWith("EN:") })
        assertNotEquals("the EN and RU schemas must differ", ruTools, enTools)
        assertEquals(
            "the default (no language) call stays byte-identical to RU",
            ruTools,
            selector.resolve(1, "q")
        )

        val enCloud = selector.resolve(1, "отправь смс маме", language = "en")
        assertTrue(
            "the retrieval path must also use the EN schemas",
            enCloud.isNotEmpty() && enCloud.all { it.description.startsWith("EN:") }
        )
    }
}
