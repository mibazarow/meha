package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `translate_chat` (checklist §3): translates the last N messages of a chat into a target language
 * with the user's OWN configured LLM — no external translation service.
 *
 * Behaviour:
 * - reads the last `limit` messages (default 20, cap 50) of `chat_id` (default: the most recent chat),
 * - sends them in ONE bounded LLM request and returns a compact per-message list (author +
 *   translation) in the original order,
 * - NEVER modifies the chat: it is a pure read plus an LLM call,
 * - empty/attachment-only messages are skipped with an honest note; too-long text is trimmed from the
 *   oldest side and reported, never silently dropped.
 *
 * All the pure logic (selection/limit/ordering, prompt, parsing, formatting) lives in
 * [selectChatLines], [buildChatTranslatePrompt], [parseChatTranslations] and [formatChatTranslation],
 * so it is unit-tested on the JVM. This class only does the I/O and the honest error mapping.
 */
class TranslateChatToolsHandler(
    private val llmClient: LlmClient,
    private val settingsStore: LlmSettingsStore,
    private val chatRepository: ChatRepository,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "translate_chat"

            override suspend fun execute(arguments: JsonObject): ToolResult =
                withContext(Dispatchers.IO) {
                    val toRaw = arguments.str("to")
                        ?: return@withContext ToolResult(
                            false,
                            "Укажи to — целевой язык, например ru, en или «немецкий»."
                        )
                    val to = TranslateLanguages.resolve(toRaw)
                        ?: return@withContext ToolResult(
                            false,
                            unsupportedLanguageMessage("Язык", toRaw, allowAuto = false)
                        )
                    val fromRaw = arguments.str("from")
                    val from = if (fromRaw == null || TranslateLanguages.isAuto(fromRaw)) {
                        null
                    } else {
                        TranslateLanguages.resolve(fromRaw)
                            ?: return@withContext ToolResult(
                                false,
                                unsupportedLanguageMessage("Исходный язык", fromRaw, allowAuto = true)
                            )
                    }

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
                            "Нет ни одного чата — переводить нечего."
                        )
                    if (requestedChatId != null && !chatExists(chatId)) {
                        return@withContext ToolResult(false, "Чат $chatId не найден.")
                    }

                    val messages = runCatching {
                        chatRepository.observeMessages(chatId).first()
                    }.getOrNull().orEmpty()
                    if (messages.isEmpty()) {
                        return@withContext ToolResult(
                            false,
                            "В чате $chatId нет сообщений — переводить нечего."
                        )
                    }

                    val requestedLimit = arguments.str("limit")?.trim()?.toIntOrNull()
                    val selection = selectChatLines(
                        messages = messages.map {
                            TranslateChatLine(chatAuthorLabel(it.role), it.content)
                        },
                        limit = clampChatLimit(requestedLimit),
                    )
                    if (selection.lines.isEmpty()) {
                        return@withContext ToolResult(false, emptySelectionMessage(selection))
                    }

                    val configured = runCatching { settingsStore.isConfigured().first() }
                        .getOrDefault(false)
                    if (!configured) {
                        return@withContext ToolResult(false, TRANSLATE_NOT_CONFIGURED)
                    }

                    val raw = try {
                        collectTranslateCompletion(
                            llmClient,
                            buildChatTranslatePrompt(selection.lines, to, from),
                        )
                    } catch (timeout: TimeoutCancellationException) {
                        return@withContext ToolResult(false, translateTimeoutMessage())
                    } catch (cancellation: CancellationException) {
                        // Never swallow a stopped turn.
                        throw cancellation
                    } catch (e: Exception) {
                        return@withContext ToolResult(false, "Ошибка перевода: ${e.message}")
                    }

                    val cleaned = cleanTranslation(raw)
                    if (cleaned.isBlank()) {
                        return@withContext ToolResult(false, TRANSLATE_EMPTY_ANSWER)
                    }
                    val parsed = parseChatTranslations(cleaned, selection.lines.size)
                    ToolResult(
                        true,
                        formatChatTranslation(selection, parsed, cleaned, to).trim(),
                    )
                }
        },
    )

    /** The most recent chat id (`observeChats` is ordered by `updatedAt DESC`), or null when empty. */
    private suspend fun mostRecentChatId(): Long? =
        runCatching { chatRepository.observeChats().first().firstOrNull()?.id }.getOrNull()

    /** True when [chatId] resolves to a stored chat; a read failure is treated as "missing". */
    private suspend fun chatExists(chatId: Long): Boolean =
        runCatching { chatRepository.observeChat(chatId).first() }.getOrNull() != null

    /** Honest reason no message could be translated at all. */
    private fun emptySelectionMessage(selection: TranslateChatSelection): String = when {
        selection.skippedEmpty > 0 && selection.droppedForLength > 0 ->
            "В последних сообщениях нет текста для перевода: ${selection.skippedEmpty} без текста, " +
                "ещё ${selection.droppedForLength} не поместились по длине " +
                "($MAX_CHAT_TRANSLATE_CHARS символов). Сократи limit."
        selection.droppedForLength > 0 ->
            "Последние сообщения слишком длинные (лимит $MAX_CHAT_TRANSLATE_CHARS символов) — " +
                "сократи limit или выбери чат с более короткими сообщениями."
        else ->
            "В последних сообщениях нет текста (только вложения) — переводить нечего."
    }
}
