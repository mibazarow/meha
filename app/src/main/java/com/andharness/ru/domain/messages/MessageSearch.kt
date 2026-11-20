package com.andharness.ru.domain.messages

import com.andharness.ru.domain.formatting.collapseWhitespace
import java.util.Locale

/**
 * One hit of the hybrid stored-message search, carrying the message's FULL body. The `message_search`
 * tool renders a bounded snippet from [text]; the service itself never truncates, so callers decide
 * how much context to expose.
 */
data class MessageSearchHit(
    val messageId: Long,
    val sourceType: String,
    val packageName: String?,
    val conversationKey: String?,
    val personId: Long?,
    val sender: String?,
    val at: Long,
    val text: String,
    /** Semantic cosine when a vector matched, else `0.0` for a lexical-only hit. */
    val score: Double,
)

/**
 * Hybrid (lexical FTS4 + dense-vector) search over `stored_messages`.
 *
 * Implementations must be best-effort: semantics degrade to lexical-only when embeddings are
 * unavailable or disabled, and a blank query yields nothing. The result is bounded by [limit] and
 * ordered by semantic score then recency.
 */
interface MessageSearch {

    /**
     * @param query free-text query; blank yields an empty list.
     * @param limit upper bound on returned hits (values &le; 0 yield an empty list).
     * @param source optional `sms|mms|messenger|email|chat|all`; `null`/`all` means no filter.
     * @param personId optional person filter; `null` means no filter.
     * @param semantic when `false`, only the lexical FTS index is searched.
     */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        source: String? = null,
        personId: Long? = null,
        semantic: Boolean = true,
    ): List<MessageSearchHit>

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}

/** Pure normalization for the `message_search` inputs (whitespace, source aliases). */
object MessageSearchNormalization {

    /** Collapses runs of whitespace to one space and trims; a blank input stays blank. */
    fun normalizeQuery(raw: String): String = collapseWhitespace(raw)

    /**
     * Maps the user-facing `source` token to the persisted [StoredMessageSource] value, or `null`
     * for "all"/absent. Unknown tokens are upper-cased verbatim so a newer source type still works.
     */
    fun normalizeSource(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (value) {
            "", "all", "*" -> null
            "sms" -> StoredMessageSource.SMS
            "mms" -> StoredMessageSource.MMS
            "messenger" -> StoredMessageSource.MESSENGER
            "email" -> StoredMessageSource.EMAIL
            "chat" -> StoredMessageSource.CHAT
            else -> value.uppercase(Locale.ROOT)
        }
    }
}

/**
 * Deterministic merge of the two independent search sources. Semantic hits (already ranked by
 * [MessageSemanticRanker]) carry their cosine; lexical-only ids get `0.0`. Ids are deduplicated by
 * message id, ordered by descending score, then newest-first (`at`), then descending id (a stable
 * tie-break), and the union is bounded to [limit]. Ids absent from [atById] are dropped, so the
 * merge can never emit a message the caller did not resolve.
 */
object MessageSearchMerger {

    fun merge(
        lexicalIds: List<Long>,
        semanticHits: List<Pair<Long, Float>>,
        atById: Map<Long, Long>,
        limit: Int,
    ): List<Pair<Long, Double>> {
        if (limit <= 0) return emptyList()
        val best = LinkedHashMap<Long, Double>()
        for ((id, cosine) in semanticHits) {
            if (id !in atById) continue
            val score = cosine.toDouble()
            val previous = best[id]
            if (previous == null || score > previous) best[id] = score
        }
        for (id in lexicalIds) {
            if (id !in atById) continue
            if (id !in best) best[id] = 0.0
        }
        return best.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Double>> { it.value }
                    .thenByDescending { atById[it.key] ?: 0L }
                    .thenByDescending { it.key }
            )
            .take(limit)
            .map { it.key to it.value }
    }
}
