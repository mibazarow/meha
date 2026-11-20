package com.andharness.ru.data.messages

import com.andharness.ru.domain.messages.MessageQueryEmbedder
import com.andharness.ru.domain.messages.MessageSearch
import com.andharness.ru.domain.messages.MessageSearchHit
import com.andharness.ru.domain.messages.MessageSearchMerger
import com.andharness.ru.domain.messages.MessageSearchNormalization
import com.andharness.ru.domain.messages.MessageSemanticRanker
import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.messages.StoredMessagesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid [MessageSearch] over `stored_messages`: lexical FTS4 ([StoredMessagesRepository.searchFts])
 * fused with dense-vector ranking ([MessageSemanticRanker]) over a BOUNDED pool of already-embedded
 * messages ([StoredMessagesRepository.recentVectors]).
 *
 * The semantic layer is best-effort: it is skipped entirely when the caller passes `semantic=false`,
 * when the embedder returns `null` (switch off / model missing / timeout) or when no indexed vectors
 * exist. In every such case the lexical hits are still returned — the search never errors because
 * semantics are unavailable.
 *
 * References:
 * - Room DAO / FTS4 candidate lookup: https://developer.android.com/training/data-storage/room/accessing-data
 * - SQLite FTS4 `MATCH`: https://www.sqlite.org/fts3.html
 * - Cosine ranking math: [MessageSemanticRanker].
 */
@Singleton
class MessageSearchServiceImpl @Inject constructor(
    private val repository: StoredMessagesRepository,
    private val queryEmbedder: MessageQueryEmbedder,
) : MessageSearch {

    override suspend fun search(
        query: String,
        limit: Int,
        source: String?,
        personId: Long?,
        semantic: Boolean,
    ): List<MessageSearchHit> = withContext(Dispatchers.IO) {
        val normalizedQuery = MessageSearchNormalization.normalizeQuery(query)
        if (normalizedQuery.isEmpty() || limit <= 0) return@withContext emptyList()
        val sourceType = MessageSearchNormalization.normalizeSource(source)

        val lexical = runCatching { repository.searchFts(normalizedQuery, limit) }
            .getOrDefault(emptyList())
            .filter { it.matches(sourceType, personId) }
        val semanticResult = if (semantic) {
            semanticHits(normalizedQuery, limit, sourceType, personId)
        } else {
            SemanticResult(emptyList(), emptyList())
        }

        val byId = LinkedHashMap<Long, StoredMessage>()
        lexical.forEach { byId[it.id] = it }
        semanticResult.messages.forEach { byId.putIfAbsent(it.id, it) }
        if (byId.isEmpty()) return@withContext emptyList()

        val atById = byId.mapValues { it.value.at }
        val ordered = MessageSearchMerger.merge(
            lexicalIds = lexical.map { it.id },
            semanticHits = semanticResult.hits,
            atById = atById,
            limit = limit,
        )
        ordered.mapNotNull { (id, score) ->
            byId[id]?.let { message -> message.toHit(score) }
        }
    }

    private data class SemanticResult(
        val hits: List<Pair<Long, Float>>,
        val messages: List<StoredMessage>,
    )

    private suspend fun semanticHits(
        query: String,
        limit: Int,
        sourceType: String?,
        personId: Long?,
    ): SemanticResult {
        val queryVector = runCatching { queryEmbedder.embed(query) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return SemanticResult(emptyList(), emptyList())
        val stored = runCatching { repository.recentVectors(SEMANTIC_SCAN_LIMIT) }
            .getOrDefault(emptyList())
        if (stored.isEmpty()) return SemanticResult(emptyList(), emptyList())

        val candidateMessages = runCatching { repository.byIds(stored.map { it.first }) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
        val allowed = stored.filter { (id, _) ->
            val message = candidateMessages[id] ?: return@filter false
            message.matches(sourceType, personId)
        }
        val ranked = MessageSemanticRanker.rank(queryVector, allowed, limit)
        return SemanticResult(ranked, ranked.mapNotNull { candidateMessages[it.first] })
    }

    private fun StoredMessage.matches(sourceType: String?, personId: Long?): Boolean =
        (sourceType == null || this.sourceType.equals(sourceType, ignoreCase = true)) &&
            (personId == null || this.personId == personId)

    private fun StoredMessage.toHit(score: Double): MessageSearchHit = MessageSearchHit(
        messageId = id,
        sourceType = sourceType,
        packageName = packageName,
        conversationKey = conversationKey,
        personId = personId,
        sender = sender,
        at = at,
        text = text,
        score = score,
    )

    private companion object {
        /** Bounded candidate pool for semantic search: the newest N stored message vectors. */
        const val SEMANTIC_SCAN_LIMIT = 400
    }
}
