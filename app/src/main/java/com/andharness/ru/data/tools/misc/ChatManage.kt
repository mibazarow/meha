package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Android-free helpers behind the chat-management tools (`chat_create`, `chat_rename`, `chat_delete`,
 * `chat_pin`, `chat_clear_messages`, `chat_export`): argument normalisation and the pure export
 * rendering. Kept pure (no Android APIs, no repository access) so every rule is unit-tested on the
 * JVM (see `ChatManageTest`). The handler only does the I/O.
 *
 * References:
 * - kotlinx.serialization JSON builders (`buildJsonArray` / `buildJsonObject`) — the export is a real,
 *   parseable JSON document, not hand-escaped text —
 *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/
 * - `Flow.first()` (a Room flow always emits at least once) is used by the handler, not here —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/first.html
 */

/** Title used by `chat_create` when the model passes no (or a blank) `title`. */
internal const val DEFAULT_CHAT_TITLE = "Новый чат"

/** Format used by `chat_export` when `format` is missing or unknown. */
internal const val DEFAULT_CHAT_EXPORT_FORMAT = "md"

/** Formats accepted by `chat_export`; anything else degrades to [DEFAULT_CHAT_EXPORT_FORMAT]. */
internal val CHAT_EXPORT_FORMATS: Set<String> = setOf("md", "txt", "json")

/** Trims and lowercases the requested format; unknown or blank → [DEFAULT_CHAT_EXPORT_FORMAT]. Pure. */
internal fun normalizeChatExportFormat(raw: String?): String =
    raw?.trim()?.lowercase()?.takeIf { it in CHAT_EXPORT_FORMATS } ?: DEFAULT_CHAT_EXPORT_FORMAT

/** File extension of a normalised [format]. Pure. */
internal fun chatExportExtension(format: String): String = when (normalizeChatExportFormat(format)) {
    "json" -> "json"
    "txt" -> "txt"
    else -> "md"
}

/**
 * Default export file name (`chat-<id>-<timestamp>.<ext>`), pure so the naming is unit-tested. The
 * directory is chosen by the handler (`<filesDir>/exports`).
 */
internal fun defaultChatExportFileName(chatId: Long, timestamp: Long, format: String): String =
    "chat-$chatId-$timestamp.${chatExportExtension(format)}"

/**
 * Renders the whole chat for `chat_export` in [format] (`md` / `txt` / `json`); unknown formats fall
 * back to Markdown. Never invents data: each message is rendered exactly as stored. Pure and
 * unit-tested.
 */
internal fun renderChatExport(messages: List<MessageEntity>, format: String): String =
    when (normalizeChatExportFormat(format)) {
        "json" -> renderChatExportJson(messages)
        "txt" -> renderChatExportText(messages)
        else -> renderChatExportMarkdown(messages)
    }

/** Markdown rendering: one `**author:** content` block per message. Pure. */
internal fun renderChatExportMarkdown(messages: List<MessageEntity>): String {
    if (messages.isEmpty()) return "Пустой чат: сообщений нет."
    return buildString {
        messages.forEach { message ->
            append("**${chatAuthorLabel(message.role)}:** ")
            append(message.content.trim())
            append("\n\n")
        }
    }.trimEnd() + "\n"
}

/** Plain-text rendering: one `author: content` line per message. Pure. */
internal fun renderChatExportText(messages: List<MessageEntity>): String {
    if (messages.isEmpty()) return "Пустой чат: сообщений нет."
    return buildString {
        messages.forEach { message ->
            append("${chatAuthorLabel(message.role)}: ")
            append(message.content.trim())
            append('\n')
        }
    }
}

/**
 * JSON rendering: an array of `{id, role, author, content, createdAt, toolName?}` objects, encoded
 * by kotlinx.serialization so the result is always valid JSON. Pure and unit-tested.
 */
internal fun renderChatExportJson(messages: List<MessageEntity>): String {
    val array = buildJsonArray {
        messages.forEach { message ->
            add(buildJsonObject {
                put("id", message.id)
                put("role", message.role)
                put("author", chatAuthorLabel(message.role))
                put("content", message.content)
                put("createdAt", message.createdAt)
                message.toolName?.let { put("toolName", it) }
            })
        }
    }
    return CHAT_EXPORT_JSON.encodeToString(JsonArray.serializer(), array)
}

/** Shared pretty-printing JSON encoder (built once, not per export). */
private val CHAT_EXPORT_JSON = AppJson.Pretty

/**
 * True only when a destructive tool's `confirm` argument was explicitly `true`. Missing, blank or
 * any other value refuses the action. Pure and unit-tested.
 */
internal fun confirmIsTrue(raw: String?): Boolean =
    raw?.trim()?.equals("true", ignoreCase = true) == true

/**
 * `pinned` argument for `chat_pin`: default `true`; only an explicit false-like value unpins. Pure.
 */
internal fun parsePinnedFlag(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
    "false", "0", "no" -> false
    else -> true
}

/**
 * Chat id the chat-management tools act on: the explicit numeric `chat_id` argument when given,
 * otherwise the id of the CURRENT agent run's chat (so «переименуй этот чат» works without the model
 * passing an id). `null` means "neither given nor in an agent run" — the tools then report the
 * missing id honestly. Pure and unit-tested.
 */
internal fun resolveChatId(explicit: Long?, current: Long?): Long? = explicit ?: current
