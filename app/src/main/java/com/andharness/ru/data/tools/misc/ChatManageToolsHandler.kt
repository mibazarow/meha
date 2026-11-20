package com.andharness.ru.data.tools.misc

import android.content.Context
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.tools.currentToolChatId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Chat-MANAGEMENT tools (`chat_create`, `chat_rename`, `chat_delete`, `chat_pin`,
 * `chat_clear_messages`, `chat_export`): the agent can create, rename, pin/unpin and clear the
 * user's own chats, delete a chat with its messages, and export a chat to a file — but ONLY when the
 * user explicitly asks.
 *
 * Bounds and honest failures:
 * - Destructive tools (`chat_delete`, `chat_clear_messages`) run only with `confirm=true`, are flagged
 *   `dangerous` in the catalog, and report the real outcome; a missing chat is an honest error.
 * - `chat_export` reads `observeMessages(chatId).first()` and renders via the pure helpers in
 *   `ChatManage.kt` (`renderChatExport`), writing to `<filesDir>/exports/…` unless `path` is given;
 *   any read/write failure is reported honestly with the real reason.
 *
 * All pure logic (format normalisation, rendering, `confirm`/`pinned` parsing, default file name)
 * lives in `ChatManage.kt` and is unit-tested on the JVM (see `ChatManageTest`). This class only does
 * the I/O.
 *
 * References:
 * - Room `Flow` from `@Query` + `Flow.first()` (one emission per read) —
 *   https://developer.android.com/training/data-storage/room/async-queries
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/first.html
 * - `Context.getFilesDir()` (app-private storage used for the default export path) —
 *   https://developer.android.com/reference/android/content/Context#getFilesDir()
 * - Deleting a chat cascades to its messages via the Room `@ForeignKey(onDelete = CASCADE)` on
 *   `messages.chatId` — https://developer.android.com/reference/androidx/room/ForeignKey
 */
class ChatManageToolsHandler(
    private val chatRepository: ChatRepository,
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        chatCreate(),
        chatRename(),
        chatDelete(),
        chatPin(),
        chatClearMessages(),
        chatExport(),
    )

    private fun chatCreate(): ToolHandler = object : ToolHandler {
        override val name = "chat_create"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val title = arguments.str("title")?.trim()?.ifBlank { DEFAULT_CHAT_TITLE }
                    ?: DEFAULT_CHAT_TITLE
                runCatching { chatRepository.createChat(title) }
                    .map { id -> ToolResult(true, "Создан чат «$title» (id $id).") }
                    .getOrElse { ToolResult(false, "Не удалось создать чат: ${it.message}") }
            }
    }

    private fun chatRename(): ToolHandler = object : ToolHandler {
        override val name = "chat_rename"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val chatId = targetChatId(arguments)
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи chat_id — числовой идентификатор чата (либо вызови инструмент из чата, " +
                            "чтобы взять текущий)."
                    )
                val title = arguments.str("title")?.trim()
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи title — новое название чата."
                    )
                val chat = runCatching { chatRepository.observeChat(chatId).first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Чат $chatId не найден.")
                runCatching { chatRepository.renameChat(chatId, title) }
                    .getOrElse { return@withContext ToolResult(false, "Не удалось переименовать чат $chatId: ${it.message}") }
                ToolResult(true, "Чат ${chat.id} переименован в «$title».")
            }
    }

    private fun chatDelete(): ToolHandler = object : ToolHandler {
        override val name = "chat_delete"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val chatId = targetChatId(arguments)
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи chat_id — числовой идентификатор чата (либо вызови инструмент из чата, " +
                            "чтобы взять текущий)."
                    )
                if (!confirmIsTrue(arguments.str("confirm"))) {
                    return@withContext ToolResult(
                        false,
                        "chat_delete отклонён: удаление чата $chatId не подтверждено (нет confirm=true)."
                    )
                }
                val chat = runCatching { chatRepository.observeChat(chatId).first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Чат $chatId не найден.")
                runCatching { chatRepository.deleteChat(chatId) }
                    .getOrElse { return@withContext ToolResult(false, "Не удалось удалить чат $chatId: ${it.message}") }
                ToolResult(
                    true,
                    "Чат ${chat.id} «${chat.title.ifBlank { "(без названия)" }}» удалён вместе с сообщениями."
                )
            }
    }

    private fun chatPin(): ToolHandler = object : ToolHandler {
        override val name = "chat_pin"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val chatId = targetChatId(arguments)
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи chat_id — числовой идентификатор чата (либо вызови инструмент из чата, " +
                            "чтобы взять текущий)."
                    )
                val pinned = parsePinnedFlag(arguments.str("pinned"))
                runCatching { chatRepository.observeChat(chatId).first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Чат $chatId не найден.")
                runCatching { chatRepository.setPinned(chatId, pinned) }
                    .getOrElse { return@withContext ToolResult(false, "Не удалось изменить закрепление чата $chatId: ${it.message}") }
                ToolResult(
                    true,
                    if (pinned) "Чат $chatId закреплён." else "Чат $chatId откреплён."
                )
            }
    }

    private fun chatClearMessages(): ToolHandler = object : ToolHandler {
        override val name = "chat_clear_messages"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val chatId = targetChatId(arguments)
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи chat_id — числовой идентификатор чата (либо вызови инструмент из чата, " +
                            "чтобы взять текущий)."
                    )
                if (!confirmIsTrue(arguments.str("confirm"))) {
                    return@withContext ToolResult(
                        false,
                        "chat_clear_messages отклонён: очистка чата $chatId не подтверждена (нет confirm=true)."
                    )
                }
                runCatching { chatRepository.observeChat(chatId).first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Чат $chatId не найден.")
                runCatching { chatRepository.clearMessages(chatId) }
                    .getOrElse { return@withContext ToolResult(false, "Не удалось очистить сообщения чата $chatId: ${it.message}") }
                ToolResult(true, "Сообщения чата $chatId очищены; сам чат сохранён.")
            }
    }

    private fun chatExport(): ToolHandler = object : ToolHandler {
        override val name = "chat_export"

        override suspend fun execute(arguments: JsonObject): ToolResult =
            withContext(Dispatchers.IO) {
                val chatId = targetChatId(arguments)
                    ?: return@withContext ToolResult(
                        false,
                        "Укажи chat_id — числовой идентификатор чата (либо вызови инструмент из чата, " +
                            "чтобы взять текущий)."
                    )
                runCatching { chatRepository.observeChat(chatId).first() }.getOrNull()
                    ?: return@withContext ToolResult(false, "Чат $chatId не найден.")
                val messages = runCatching { chatRepository.observeMessages(chatId).first() }
                    .getOrElse { return@withContext ToolResult(false, "Не удалось прочитать сообщения чата $chatId: ${it.message}") }

                val format = normalizeChatExportFormat(arguments.str("format"))
                val content = renderChatExport(messages, format)
                val file = exportTarget(arguments.str("path"), chatId, format)
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(content, Charsets.UTF_8)
                }.getOrElse { return@withContext ToolResult(false, "Не удалось записать файл экспорта: ${it.message}") }

                ToolResult(
                    true,
                    "Экспорт чата $chatId сохранён: ${file.absolutePath} " +
                        "(сообщений: ${messages.size}, формат: $format)."
                )
            }
    }

    /**
     * Chat the tool acts on: the explicit numeric `chat_id` argument when given, otherwise the chat
     * of the CURRENT agent run. The current id comes from the per-run
     * [com.andharness.ru.domain.tools.ToolExecutionContext] coroutine element the orchestrator
     * installs around the tool loop (`AgentOrchestratorImpl.executeLoop`), read through
     * [currentToolChatId] — so two concurrent runs can never see each other's chat, and a `chat_id`
     * the model omitted defaults to the chat it is talking in. Returns null outside a run and with no
     * explicit id; the caller then reports the missing id honestly.
     */
    private suspend fun targetChatId(arguments: JsonObject): Long? =
        resolveChatId(parseChatId(arguments), currentToolChatId())

    /** Numeric `chat_id` argument, or null when missing / not a number. */
    private fun parseChatId(arguments: JsonObject): Long? =
        arguments.str("chat_id")?.trim()?.toLongOrNull()

    /**
     * Where `chat_export` writes: an explicit absolute `path` when given, otherwise
     * `<filesDir>/exports/chat-<id>-<timestamp>.<ext>` (the pure name comes from `ChatManage.kt`).
     */
    private fun exportTarget(rawPath: String?, chatId: Long, format: String): File {
        val explicit = rawPath?.trim()
        if (!explicit.isNullOrEmpty()) return File(explicit)
        val dir = File(context.filesDir, CHAT_EXPORT_DIR_NAME)
        return File(dir, defaultChatExportFileName(chatId, System.currentTimeMillis(), format))
    }

    private companion object {
        /** Subdirectory of `filesDir` holding default chat exports. */
        const val CHAT_EXPORT_DIR_NAME = "exports"
    }
}
