package com.andharness.ru.data.agent

import android.util.Log
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.tools.ToolRegistry
import com.andharness.ru.domain.tools.ToolRetriever
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Resolves the tool schemas to expose for one agent turn.
 *
 * Core + BM25-relevant + explicitly named + recently used tools ([ToolRetriever], K =
 * [ToolRetriever.DEFAULT_K]), with a bounded safe-set fallback (core ∪ explicit ∪ recent, capped at
 * [ToolRetriever.MAX_FALLBACK_TOOLS]) whenever retrieval is untrustworthy. The full catalog is NEVER
 * sent; `tool_search` (always in the core set) is the escape hatch that keeps any catalog tool
 * reachable by name.
 */
class RunToolSelector @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val repository: ChatRepository,
    private val mcpTools: McpDynamicTools
) {

    /** Tool schemas to expose for this turn (see class KDoc). */
    suspend fun resolve(
        chatId: Long,
        userMessage: String,
        language: String = AppLanguage.DEFAULT
    ): List<LlmTool> {
        // The catalog is static tools PLUS the dynamic tools of the enabled MCP servers. MCP is
        // fail-safe: an unavailable/empty registry must leave the static behavior exactly as before.
        val dynamic = (runCatching { mcpTools.toolDefinitions() }.getOrNull() ?: emptyList())
            .map { it.toLlmTool() }
        val all = toolRegistry.definitions(language) + dynamic
        val k = ToolRetriever.DEFAULT_K
        val messages = runCatching { repository.observeMessages(chatId).first() }.getOrDefault(emptyList())
        val recent = messages.filter { it.role == "tool" }
            .mapNotNull { it.toolName }
            .takeLast(ToolRetriever.RECENT_TOOLS)
        val query = buildRetrievalQuery(userMessage, messages)
        // Dynamic MCP tools are always exposed while their server is enabled: they are the point of
        // connecting the server, and the model cannot "guess" them from the static vocabulary.
        val core = ToolRetriever.CORE_TOOLS + dynamic.map { it.name }
        val selection = ToolRetriever.select(all, query, recent, k, core = core)
        val fallback = if (selection.degraded) ", откат к безопасному набору (нет лексических совпадений)" else ""
        val mcpNote = if (dynamic.isEmpty()) "" else ", MCP ${dynamic.size}"
        Log.i(
            TAG,
            "Tool-RAG: режим=RETRIEVAL, отправлено ${selection.tools.size} из ${all.size} " +
                "(BM25 совпало ${selection.matched}, K=$k, недавних ${recent.size}$mcpNote)$fallback"
        )
        return selection.tools
    }

    /**
     * Retrieval query for one turn: the current user text plus a bounded tail of the conversation,
     * so a follow-up ("сделай это ещё раз") still carries the earlier topic.
     */
    private fun buildRetrievalQuery(userMessage: String, messages: List<MessageEntity>): String {
        val history = messages.asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .map { it.content }
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(RECENT_QUERY_MESSAGES)
            .joinToString("\n") { it.take(RECENT_QUERY_CHARS) }
        return if (history.isBlank()) userMessage else "$userMessage\n$history"
    }

    private companion object {
        private const val TAG = "Andharness"

        // How much conversation tail feeds the tool-retrieval query (update.md idea #1).
        private const val RECENT_QUERY_MESSAGES = 6
        private const val RECENT_QUERY_CHARS = 400
    }
}
