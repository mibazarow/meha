package com.andharness.ru.data.chat

import com.andharness.ru.data.db.dao.ChatDao
import com.andharness.ru.data.db.dao.ChatMessageVectorDao
import com.andharness.ru.data.db.dao.MessageDao
import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatMessageCandidate
import com.andharness.ru.domain.chat.ChatMessageMatch
import com.andharness.ru.domain.chat.ChatSearch
import com.andharness.ru.domain.chat.ChatSearchFusion
import com.andharness.ru.domain.chat.ChatSearchResult
import com.andharness.ru.domain.chat.ChatTitleMatch
import com.andharness.ru.domain.messages.MessageQueryEmbedder
import com.andharness.ru.domain.messages.MessageSearchNormalization
import com.andharness.ru.domain.messages.MessageSemanticRanker
import com.andharness.ru.domain.retrieval.VectorCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default [ChatSearch] over the app's OWN chats: a chat-TITLE `contains` match fused with a bounded
 * LEXICAL message pool and a bounded SEMANTIC message pool over `chat_message_vectors` (the index
 * written by [ChatMessageVectorIndexer]).
 *
 * The three signals are fused by the pure [ChatSearchFusion]; this class only does bounded I/O and
 * the on-device query embedding, all on [Dispatchers.Default]. The semantic layer is best-effort:
 * it is skipped entirely when the query embedding is `null` (embedder off / model missing / the
 * `semanticSearchEnabled` gate, handled by [MessageQueryEmbedder]), when no vectors exist, or when a
 * stored vector is corrupt — the lexical and title hits are still returned, so search never errors
 * because semantics are unavailable.
 *
 * Bounds (all SQL `LIMIT`, so no unbounded scan):
 * - [CHAT_SCAN_LIMIT] chats for the title pool,
 * - [MESSAGE_SCAN_LIMIT] newest messages for the lexical pool,
 * - [SEMANTIC_SCAN_LIMIT] newest vectors for the semantic pool (mirrors
 *   `MessageSearchServiceImpl.SEMANTIC_SCAN_LIMIT`).
 *
 * References:
 * - Room DAO `@Query` / suspend queries: https://developer.android.com/training/data-storage/room/accessing-data
 *   and https://developer.android.com/training/data-storage/room/async-queries
 * - `withContext(Dispatchers.Default)`: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html
 * - `String.contains(ignoreCase = true)`: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/contains.html
 */
@Singleton
class ChatSearchServiceImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val vectorDao: ChatMessageVectorDao,
    private val queryEmbedder: MessageQueryEmbedder,
) : ChatSearch {

    override suspend fun searchChats(query: String, limit: Int): List<ChatSearchResult> =
        withContext(Dispatchers.Default) {
            val normalized = MessageSearchNormalization.normalizeQuery(query)
            if (normalized.isEmpty() || limit <= 0) return@withContext emptyList()

            val chats = runCatching { chatDao.recent(CHAT_SCAN_LIMIT) }.getOrDefault(emptyList())
            val titleMatches = chats
                .filter { it.title.contains(normalized, ignoreCase = true) }
                .map { ChatTitleMatch(it.id, it.title) }

            val lexical = runCatching { messageDao.recentVisible(MESSAGE_SCAN_LIMIT) }
                .getOrDefault(emptyList())
                .filter { it.content.isNotBlank() && it.content.contains(normalized, ignoreCase = true) }

            val semanticHits = semanticHits(normalized, chatId = null)
            val candidatesById = resolveCandidates(lexical, semanticHits, restrictToChatId = null)
            if (candidatesById.isEmpty() && titleMatches.isEmpty()) return@withContext emptyList()

            val titleByChat = titleMap(chats, candidatesById.values.mapTo(HashSet()) { it.chatId })

            ChatSearchFusion.fuseChats(
                query = normalized,
                titleMatches = titleMatches,
                lexicalIds = lexical.map { it.id },
                semanticHits = semanticHits,
                candidates = candidatesById.mapValues { (_, message) ->
                    message.toCandidate(titleByChat[message.chatId] ?: "")
                },
                limit = limit,
            )
        }

    override suspend fun searchInChat(
        chatId: Long,
        query: String,
        limit: Int,
    ): List<ChatMessageMatch> = withContext(Dispatchers.Default) {
        val normalized = MessageSearchNormalization.normalizeQuery(query)
        if (chatId <= 0L || normalized.isEmpty() || limit <= 0) return@withContext emptyList()

        val lexical = runCatching { messageDao.recentVisibleInChat(chatId, MESSAGE_SCAN_LIMIT) }
            .getOrDefault(emptyList())
            .filter { it.content.isNotBlank() && it.content.contains(normalized, ignoreCase = true) }

        val semanticHits = semanticHits(normalized, chatId)
        val candidatesById = resolveCandidates(lexical, semanticHits, restrictToChatId = chatId)
        if (candidatesById.isEmpty()) return@withContext emptyList()

        ChatSearchFusion.rankMessages(
            query = normalized,
            lexicalIds = lexical.map { it.id },
            semanticHits = semanticHits,
            candidates = candidatesById.mapValues { (_, message) -> message.toCandidate("") },
            limit = limit,
        )
    }

    /**
     * Ranks the newest [SEMANTIC_SCAN_LIMIT] chat-message vectors (optionally scoped to [chatId])
     * against [query]. Returns an empty list — never throws — when the query cannot be embedded, no
     * vectors exist, or every stored vector is corrupt.
     */
    private suspend fun semanticHits(query: String, chatId: Long?): List<Pair<Long, Float>> {
        val queryVector = runCatching { queryEmbedder.embed(query) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val rows = runCatching {
            if (chatId != null) {
                vectorDao.recentInChat(chatId, SEMANTIC_SCAN_LIMIT)
            } else {
                vectorDao.recent(SEMANTIC_SCAN_LIMIT)
            }
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()
        val vectors = rows.mapNotNull { row ->
            val decoded = VectorCodec.decode(row.vector)
            if (decoded.isEmpty()) null else row.messageId to decoded
        }
        if (vectors.isEmpty()) return emptyList()
        return MessageSemanticRanker.rank(queryVector, vectors, SEMANTIC_SCAN_LIMIT)
    }

    /**
     * Resolves the union of the lexical and semantic candidate ids to their messages, one bounded
     * round-trip for the ids the lexical pool did not already carry. [restrictToChatId] keeps only
     * messages of that chat (in-chat search).
     */
    private suspend fun resolveCandidates(
        lexical: List<MessageEntity>,
        semanticHits: List<Pair<Long, Float>>,
        restrictToChatId: Long?,
    ): Map<Long, MessageEntity> {
        val byId = LinkedHashMap<Long, MessageEntity>()
        lexical.forEach { byId[it.id] = it }
        val missing = semanticHits.map { it.first }.filter { it !in byId }.distinct()
        if (missing.isNotEmpty()) {
            runCatching { messageDao.byIds(missing) }.getOrDefault(emptyList())
                .filter { restrictToChatId == null || it.chatId == restrictToChatId }
                .forEach { byId[it.id] = it }
        }
        return byId
    }

    /** Chat-id → title for the known chats plus any [needed] id missing from that pool. */
    private suspend fun titleMap(
        known: List<ChatEntity>,
        needed: Set<Long>,
    ): Map<Long, String> {
        val titles = HashMap<Long, String>(known.size)
        known.forEach { titles[it.id] = it.title }
        val missing = needed.filter { it !in titles }
        if (missing.isNotEmpty()) {
            runCatching { chatDao.byIds(missing) }.getOrDefault(emptyList())
                .forEach { titles[it.id] = it.title }
        }
        return titles
    }

    private fun MessageEntity.toCandidate(chatTitle: String): ChatMessageCandidate =
        ChatMessageCandidate(
            messageId = id,
            chatId = chatId,
            chatTitle = chatTitle,
            text = content,
            at = createdAt,
        )

    private companion object {
        /** Bounded chat pool for title matching (the app keeps at most a few hundred chats). */
        const val CHAT_SCAN_LIMIT = 500

        /** Bounded lexical message pool: the newest messages across all chats. */
        const val MESSAGE_SCAN_LIMIT = 2_000

        /** Bounded semantic pool: the newest chat-message vectors (mirrors captured-message search). */
        const val SEMANTIC_SCAN_LIMIT = 400
    }
}
