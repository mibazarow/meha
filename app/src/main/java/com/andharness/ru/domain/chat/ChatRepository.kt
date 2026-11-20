package com.andharness.ru.domain.chat

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Aggregates chat and message persistence over the Room DAOs.
 *
 * For the M0 scaffold the domain layer references the Room entities directly
 * ([ChatEntity], [MessageEntity]); this is a deliberate simplification that will be
 * replaced by proper domain models in a later milestone.
 */
interface ChatRepository {
    fun observeChats(): Flow<List<ChatEntity>>
    fun observeChat(chatId: Long): Flow<ChatEntity?>
    suspend fun createChat(title: String): Long

    /**
     * True when a chat with [chatId] still exists. Background owners (e.g. an objective bound to a
     * chat) must check this before writing messages: the chat may have been deleted, and inserting
     * a message for a missing `chatId` violates the `messages` FOREIGN KEY.
     */
    suspend fun chatExists(chatId: Long): Boolean
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    /**
     * The last user/assistant message of each chat, keyed by [MessageEntity.chatId] — one query for
     * the whole list, so the chat list does not fan out into per-row lookups (N+1). Chats with no
     * messages yet simply do not appear.
     *
     * Defaults to an empty flow so simple in-memory fakes (tests that do not exercise the chat list)
     * stay source-compatible; the production [com.andharness.ru.data.chat.ChatRepositoryImpl]
     * overrides it.
     */
    fun observeLastMessages(): Flow<List<MessageEntity>> = emptyFlow()
    suspend fun addMessage(
        chatId: Long,
        role: String,
        content: String,
        toolName: String? = null
    ): Long

    /**
     * Like [addMessage] but also persists the optional structured [payload] (opaque JSON) on the
     * row, which the chat renders as a rich preview (e.g. `device_search` result cards). The default
     * implementation drops the payload and delegates to [addMessage], so simple in-memory fakes stay
     * source-compatible; the production implementation stores it.
     */
    suspend fun addMessageWithPayload(
        chatId: Long,
        role: String,
        content: String,
        toolName: String? = null,
        payload: String? = null
    ): Long = addMessage(chatId, role, content, toolName)

    suspend fun updateMessageContent(messageId: Long, content: String)
    suspend fun setStreaming(messageId: Long, isStreaming: Boolean)

    /**
     * Clears every persisted `isStreaming=1` flag. Called once at app startup: a live stream cannot
     * cross a process restart, so any such row is stale. Returns the number of rows reset.
     */
    suspend fun clearStaleStreaming(): Int
    suspend fun deleteChat(chatId: Long)
    suspend fun renameChat(chatId: Long, title: String)
    suspend fun updateSummary(chatId: Long, summary: String)

    /**
     * Persists the opaque context-compaction bookkeeping for [chatId] (currently the id of the last
     * message folded into [updateSummary]). Lets the next compile replay only the newer messages
     * instead of re-summarising the whole history.
     */
    suspend fun updateContextState(chatId: Long, state: String?)

    /** Deletes the oldest chats so that at most [keep] remain. [keep] <= 0 means unlimited (no-op). */
    suspend fun pruneToLimit(keep: Int)

    /** Deletes every chat and all of their messages. */
    suspend fun deleteAllChats()

    /**
     * Pins [chatId] (stamps `pinnedAt = now`) or unpins it (`pinnedAt = 0`). Pinned chats float to
     * the top of the list; `updatedAt` is never touched, so recall ordering is unaffected.
     */
    suspend fun setPinned(chatId: Long, pinned: Boolean)

    /** Deletes only [chatId]'s messages; the chat row itself and other chats are untouched. */
    suspend fun clearMessages(chatId: Long)

    /**
     * The most recently updated chat id, or null when there are no chats. Backed by an explicit
     * `ORDER BY updatedAt DESC LIMIT 1`, so pinned chats never hijack the default (`chat_recall`).
     */
    suspend fun mostRecentChatId(): Long?
}

/**
 * Maps the intended pin state to the stored `pinnedAt` marker: epoch millis when [pinned], else 0.
 * Pure so the mapping is unit-tested on the JVM without Room.
 */
fun pinnedAtFor(pinned: Boolean, now: Long): Long = if (pinned) now else 0L
