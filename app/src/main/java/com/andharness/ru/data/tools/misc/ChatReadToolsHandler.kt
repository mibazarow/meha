package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** Honest "no model configured" message: a summary is impossible without an LLM. */
private const val CHAT_RECALL_NOT_CONFIGURED =
    "Модель не настроена: задай endpoint, модель и ключ в настройках — без этого резюме невозможно."

/** Honest empty-answer message. */
private const val CHAT_RECALL_EMPTY_ANSWER = "Модель вернула пустой ответ — резюме не получено."

/** Honest timeout message built from the shared [CHAT_RECALL_TIMEOUT_MS] budget. */
private fun chatRecallTimeoutMessage(): String =
    "Модель не ответила за ${CHAT_RECALL_TIMEOUT_MS / 1000} с — резюме не получено."

/**
 * On-demand cross-chat READ tools (`chat_list`, `chat_search`, `chat_recall`): the agent can
 * enumerate the user's chats, search across chat titles and message text, and summarise another chat
 * with one LLM call — but ONLY when the user explicitly asks. Everything is returned as plain tool
 * output: nothing is ever auto-injected into the current chat, and the current chat is never changed.
 *
 * Bounds and honest failures:
 * - `chat_list`: default 20 / cap 50 chats, each with a message count and a 120-char last-message
 *   preview; an empty account is a valid answer, not an error.
 * - `chat_search`: NEWEST 500 messages of the 200 most recent chats, at most 3 snippets of 120 chars
 *   per chat, cap 20/50 chats; "nothing found" is a successful, honest answer.
 * - `chat_recall`: NEWEST 30 (cap 100) messages joined up to 16 000 chars, ONE completion under a
 *   45 s budget; a missing chat, an empty chat, an unconfigured model, a timeout and an empty model
 *   answer all produce an honest error and never fabricated content.
 *
 * All pure logic (limits, selection/ordering, preview/snippets, prompt and rendering) lives in
 * `ChatRead.kt` and is unit-tested on the JVM (see `ChatReadTest`). This class only does the I/O.
 *
 * References:
 * - kotlinx.coroutines `withTimeout` (hard wall-clock budget; throws `TimeoutCancellationException`) —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html
 * - kotlinx.coroutines `Flow.first()` (reads one emission from a Room flow) —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/first.html
 * - In-repo LLM contract: `domain/agent/LlmClient.kt` (`fun complete(request): Flow<LlmEvent>`);
 *   OpenAI-compatible request/`messages` shape —
 *   https://platform.openai.com/docs/api-reference/chat/create
 * - `BroadcastReceiver`/tool dispatch is unchanged; no new Android permission is required (the tools
 *   only read the app's own encrypted Room database through `ChatRepository`).
 */
class ChatReadToolsHandler(
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
    private val chatRepository: ChatRepository,
) {

    fun handlers(): List<ToolHandler> = listOf(chatList(), chatSearch(), chatRecall())

    private fun chatList(): ToolHandler = object : ToolHandler {
        override val name = "chat_list"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val limit = clampChatListLimit(arguments.str("limit")?.trim()?.toIntOrNull())
                val chats = runCatching { chatRepository.observeChats().first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Не удалось прочитать список чатов.")
                val selected = chats.take(limit)
                val messagesByChat = loadMessages(selected.map { it.id })
                ToolResult(true, formatChatList(buildChatSummaries(selected, messagesByChat, limit)))
            }
    }

    private fun chatSearch(): ToolHandler = object : ToolHandler {
        override val name = "chat_search"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val query = arguments.str("query")
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи query — текст для поиска по названиям чатов и сообщениям."
                    )
                val limit = clampChatSearchLimit(arguments.str("limit")?.trim()?.toIntOrNull())
                val chats = runCatching { chatRepository.observeChats().first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Не удалось прочитать список чатов.")
                val scanned = chats.take(CHAT_SEARCH_MAX_SCAN_CHATS)
                val messagesByChat = loadMessages(scanned.map { it.id })
                val hits = searchChats(scanned, messagesByChat, query, limit)
                ToolResult(true, formatChatSearch(query, hits, scanned.size))
            }
    }

    private fun chatRecall(): ToolHandler = object : ToolHandler {
        override val name = "chat_recall"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val chatIdRaw = arguments.str("chat_id")
                val requestedChatId = chatIdRaw?.trim()?.toLongOrNull()
                if (chatIdRaw != null && requestedChatId == null) {
                    return@withContext ToolResult(
                        false,
                        "chat_id должен быть числовым идентификатором чата."
                    )
                }

                val chatId = requestedChatId ?: mostRecentChatId()
                    ?: return@withContext ToolResult(
                        false,
                        "Нет ни одного чата — резюмировать нечего."
                    )
                val chat = runCatching { chatRepository.observeChat(chatId).first() }.getOrNull()
                if (chat == null) {
                    return@withContext ToolResult(
                        false,
                        if (requestedChatId != null) {
                            "Чат $chatId не найден."
                        } else {
                            "Не удалось прочитать чат $chatId."
                        }
                    )
                }

                val messages = runCatching {
                    chatRepository.observeMessages(chatId).first()
                }.getOrNull().orEmpty()
                if (messages.isEmpty()) {
                    return@withContext ToolResult(
                        false,
                        "В чате $chatId нет сообщений — резюмировать нечего."
                    )
                }

                val limit = clampChatRecallLimit(arguments.str("limit")?.trim()?.toIntOrNull())
                val selection = selectChatRecallLines(messages, limit)
                if (selection.lines.isEmpty()) {
                    return@withContext ToolResult(false, emptyRecallSelectionMessage(selection))
                }

                val configured = runCatching { settingsStore.isConfigured().first() }
                    .getOrDefault(false)
                if (!configured) {
                    return@withContext ToolResult(false, CHAT_RECALL_NOT_CONFIGURED)
                }

                val raw = try {
                    collectChatSummary(buildChatRecallPrompt(selection.lines))
                } catch (timeout: TimeoutCancellationException) {
                    return@withContext ToolResult(false, chatRecallTimeoutMessage())
                } catch (cancellation: CancellationException) {
                    // Never swallow a stopped turn.
                    throw cancellation
                } catch (e: Exception) {
                    return@withContext ToolResult(false, "Ошибка резюмирования: ${e.message}")
                }

                val summary = raw.trim()
                if (summary.isEmpty()) {
                    return@withContext ToolResult(false, CHAT_RECALL_EMPTY_ANSWER)
                }
                ToolResult(true, formatChatRecall(chatId, chat.title, selection, summary))
            }
    }

    /** One `observeMessages` read per chat id; a per-chat read failure degrades to an empty list. */
    private suspend fun loadMessages(ids: List<Long>): Map<Long, List<MessageEntity>> {
        val result = LinkedHashMap<Long, List<MessageEntity>>(ids.size)
        for (id in ids) {
            result[id] = runCatching { chatRepository.observeMessages(id).first() }
                .getOrDefault(emptyList())
        }
        return result
    }

    /**
     * The most recent chat id by `updatedAt`, or null when empty. Delegated to the repository's
     * dedicated `ORDER BY updatedAt DESC LIMIT 1` query — NOT the pinned-first list ordering — so
     * pinning a chat never hijacks the default `chat_recall` target.
     */
    private suspend fun mostRecentChatId(): Long? =
        runCatching { chatRepository.mostRecentChatId() }.getOrNull()

    /**
     * Runs the SINGLE summarisation completion under the hard [CHAT_RECALL_TIMEOUT_MS] budget. A
     * `LlmEvent.Error` becomes an exception so the caller's failure path is uniform; a foreign
     * cancellation (a stopped turn) is never swallowed.
     */
    private suspend fun collectChatSummary(userPrompt: String): String =
        withTimeout(CHAT_RECALL_TIMEOUT_MS) {
            val text = StringBuilder()
            llmClient.complete(
                LlmRequest(
                    messages = listOf(
                        LlmMessage("system", CHAT_RECALL_SYSTEM_PROMPT),
                        LlmMessage("user", userPrompt),
                    )
                )
            ).collect { event ->
                when (event) {
                    is LlmEvent.Text -> text.append(event.delta)
                    is LlmEvent.Error -> throw IllegalStateException(event.message)
                    else -> Unit
                }
            }
            text.toString()
        }
}
