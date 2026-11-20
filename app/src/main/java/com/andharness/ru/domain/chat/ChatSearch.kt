package com.andharness.ru.domain.chat

/**
 * One chat returned by the cross-chat search. [snippet] is a short window around the best matching
 * message (empty for a title-only hit), [matchedByTitle] tells the UI the chat NAME itself matched,
 * and [score] is the fused rank (title > semantic cosine > lexical-only).
 */
data class ChatSearchResult(
    val chatId: Long,
    val title: String,
    val snippet: String,
    val matchedByTitle: Boolean,
    val score: Double,
)

/** One message returned by the in-chat search, with a bounded [snippet] and fused [score]. */
data class ChatMessageMatch(
    val messageId: Long,
    val snippet: String,
    val score: Double,
    val timestamp: Long,
)

/**
 * Search over the app's OWN chats: chat TITLE (lexical, case-insensitive) plus message CONTENT
 * (lexical + semantic). This is the read side of the chat-message embedding index in
 * `chat_message_vectors`; the write side is
 * [com.andharness.ru.data.chat.ChatMessageVectorIndexer].
 *
 * Implementations must be best-effort: when the on-device embedder is off/unavailable (the
 * `semanticSearchEnabled` gate, honoured by
 * [com.andharness.ru.domain.messages.MessageQueryEmbedder]) semantics silently degrade to
 * lexical-only, and a blank query or non-positive limit yields an empty list. Results are bounded by
 * [limit] and grouped per chat (cross-chat) or per message (in-chat).
 */
interface ChatSearch {

    /** Chats matching [query] by title or by message content, best first, at most [limit]. */
    suspend fun searchChats(query: String, limit: Int = DEFAULT_LIMIT): List<ChatSearchResult>

    /** Messages of [chatId] matching [query] by content, best first, at most [limit]. */
    suspend fun searchInChat(
        chatId: Long,
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<ChatMessageMatch>

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}
