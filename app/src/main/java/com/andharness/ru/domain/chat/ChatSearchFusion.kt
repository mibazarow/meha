package com.andharness.ru.domain.chat

/**
 * A chat matched by its TITLE (case-insensitive `contains`). Produced by the service from the
 * `chats` table and fused with message hits by [ChatSearchFusion].
 */
data class ChatTitleMatch(val chatId: Long, val title: String)

/**
 * The metadata of ONE candidate message: enough to group it under its chat, order it by recency and
 * build a snippet. Produced by the service from `messages` / `chats`; consumed by [ChatSearchFusion]
 * so the fusion stays pure and JVM-testable (no Room, no Android).
 */
data class ChatMessageCandidate(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String,
    val text: String,
    val at: Long,
)

/**
 * Pure-JVM fusion of the three search signals over the app's own chats:
 * - a TITLE match (weight [TITLE_SCORE]),
 * - a LEXICAL message match (floor [LEXICAL_SCORE]),
 * - a SEMANTIC message match (the cosine from
 *   [com.andharness.ru.domain.messages.MessageSemanticRanker]).
 *
 * Messages are scored by the max of their lexical floor and semantic cosine (a literal match is a
 * real signal, but a strong paraphrase ranks above it); each chat keeps its single best-scoring
 * message, deduplicated per chat. Chats are ordered by fused score, then by the recency of their
 * best message, then by id (a deterministic tie-break) and bounded to `limit`.
 *
 * This object is deliberately free of Android/Room so every ordering, dedup and bound rule is
 * unit-tested on the JVM (see `ChatSearchFusionTest`).
 */
object ChatSearchFusion {

    /** A title hit is the strongest signal: the chat NAME itself matched the query. */
    const val TITLE_SCORE = 1.0

    /** A lexical message hit with no semantic score still counts, but below a strong semantic match. */
    const val LEXICAL_SCORE = 0.5

    /**
     * Fuses the signals into per-chat results, best first, at most [limit]. [candidates] is keyed by
     * message id and carries the grouping/snippet metadata; ids present in neither [lexicalIds] nor
     * [semanticHits] are ignored, so the fusion never invents a hit.
     */
    fun fuseChats(
        query: String,
        titleMatches: List<ChatTitleMatch>,
        lexicalIds: List<Long>,
        semanticHits: List<Pair<Long, Float>>,
        candidates: Map<Long, ChatMessageCandidate>,
        limit: Int,
    ): List<ChatSearchResult> {
        if (limit <= 0) return emptyList()

        val titleMatchIds = titleMatches.mapTo(HashSet()) { it.chatId }
        val titleByChat = HashMap<Long, String>()
        for (match in titleMatches) titleByChat[match.chatId] = match.title
        for (candidate in candidates.values) titleByChat.putIfAbsent(candidate.chatId, candidate.chatTitle)

        val lexicalSet = lexicalIds.toHashSet()
        val semanticById = bestSemanticById(semanticHits)

        val bestByChat = HashMap<Long, BestMessage>()
        for ((id, candidate) in candidates) {
            val score = fusedMessageScore(id, lexicalSet, semanticById) ?: continue
            val previous = bestByChat[candidate.chatId]
            if (previous == null || isBetter(id, score, candidate, previous)) {
                bestByChat[candidate.chatId] = BestMessage(id, score, candidate.at, candidate.text)
            }
        }

        val chatIds = LinkedHashSet<Long>()
        chatIds += titleMatchIds
        chatIds += bestByChat.keys

        return chatIds
            .map { chatId ->
                val matchedByTitle = chatId in titleMatchIds
                val best = bestByChat[chatId]
                val score = maxOf(best?.score ?: 0.0, if (matchedByTitle) TITLE_SCORE else 0.0)
                ChatSearchResult(
                    chatId = chatId,
                    title = titleByChat[chatId] ?: "",
                    snippet = best?.text?.let { ChatSnippetExtractor.extract(it, query) } ?: "",
                    matchedByTitle = matchedByTitle,
                    score = score,
                )
            }
            .sortedWith(
                compareByDescending<ChatSearchResult> { it.score }
                    .thenByDescending { bestByChat[it.chatId]?.at ?: 0L }
                    .thenByDescending { it.chatId }
            )
            .take(limit)
    }

    /**
     * Ranks individual messages of a single chat (in-chat search): dedup by message id, order by
     * fused score, then recency, then id, bounded to [limit]. Same scoring rules as [fuseChats].
     */
    fun rankMessages(
        query: String,
        lexicalIds: List<Long>,
        semanticHits: List<Pair<Long, Float>>,
        candidates: Map<Long, ChatMessageCandidate>,
        limit: Int,
    ): List<ChatMessageMatch> {
        if (limit <= 0) return emptyList()

        val lexicalSet = lexicalIds.toHashSet()
        val semanticById = bestSemanticById(semanticHits)

        return candidates.keys
            .mapNotNull { id -> fusedMessageScore(id, lexicalSet, semanticById)?.let { id to it } }
            .sortedWith(
                compareByDescending<Pair<Long, Double>> { it.second }
                    .thenByDescending { candidates[it.first]?.at ?: 0L }
                    .thenByDescending { it.first }
            )
            .take(limit)
            .map { (id, score) ->
                val candidate = candidates.getValue(id)
                ChatMessageMatch(
                    messageId = id,
                    snippet = ChatSnippetExtractor.extract(candidate.text, query),
                    score = score,
                    timestamp = candidate.at,
                )
            }
    }

    /** Keeps the best (highest) cosine per message id; a repeated hit never lowers the score. */
    private fun bestSemanticById(semanticHits: List<Pair<Long, Float>>): Map<Long, Double> {
        val semantic = HashMap<Long, Double>()
        for ((id, cosine) in semanticHits) {
            val score = cosine.toDouble()
            if (score > (semantic[id] ?: Double.NEGATIVE_INFINITY)) semantic[id] = score
        }
        return semantic
    }

    /** `null` when the message matched neither lexically nor semantically. */
    private fun fusedMessageScore(
        id: Long,
        lexicalIds: Set<Long>,
        semanticById: Map<Long, Double>,
    ): Double? {
        val semantic = semanticById[id]
        val lexical = if (id in lexicalIds) LEXICAL_SCORE else null
        return when {
            semantic == null -> lexical
            lexical == null -> semantic
            else -> maxOf(semantic, lexical)
        }
    }

    private fun isBetter(id: Long, score: Double, candidate: ChatMessageCandidate, previous: BestMessage): Boolean =
        score > previous.score ||
            (
                score == previous.score &&
                    (candidate.at > previous.at || (candidate.at == previous.at && id > previous.messageId))
                )

    private data class BestMessage(
        val messageId: Long,
        val score: Double,
        val at: Long,
        val text: String,
    )
}
