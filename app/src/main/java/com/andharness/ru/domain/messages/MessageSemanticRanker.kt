package com.andharness.ru.domain.messages

import com.andharness.ru.domain.retrieval.TextRetriever

/**
 * Pure-JVM meaning-based ranking for stored messages (the semantic layer of `message_search`).
 *
 * Messages are embedded once by the background embedder and stored as dense vectors keyed by message
 * id (`stored_message_vectors`). This ranker compares a query embedding against a BOUNDED candidate
 * pool of those vectors with [TextRetriever.cosine] and returns the best matches, so a query can
 * reach a paraphrase the lexical FTS4 index never indexed. It mirrors
 * [com.andharness.ru.domain.communications.TranscriptSemanticRanker] exactly; the caller embeds the
 * query and loads the stored vectors, this object only does the vector math.
 *
 * Cosine references (canonical definitions the implementation follows):
 * - https://en.wikipedia.org/wiki/Cosine_similarity
 * - scikit-learn `cosine_similarity` (dot / product-of-norms):
 *   https://scikit-learn.org/stable/modules/generated/sklearn.metrics.pairwise.cosine_similarity.html
 */
object MessageSemanticRanker {

    /**
     * Rankings are deterministic: equal cosines are ordered by ascending message id, so the same
     * inputs always produce the same output. Only a finite, strictly positive cosine is a hit.
     */
    fun rank(
        query: FloatArray,
        candidates: List<Pair<Long, FloatArray>>,
        limit: Int,
    ): List<Pair<Long, Float>> {
        if (limit <= 0 || query.isEmpty() || candidates.isEmpty()) return emptyList()
        return candidates
            .mapNotNull { (id, vector) ->
                val cosine = TextRetriever.cosine(query, vector)
                if (cosine.isFinite() && cosine > 0.0) id to cosine.toFloat() else null
            }
            .sortedWith(
                compareByDescending<Pair<Long, Float>> { it.second }
                    .thenBy { it.first }
            )
            .take(limit)
    }
}
