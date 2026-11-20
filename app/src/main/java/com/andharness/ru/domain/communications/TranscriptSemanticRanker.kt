package com.andharness.ru.domain.communications

import com.andharness.ru.domain.retrieval.TextRetriever

/**
 * Pure-JVM meaning-based ranking for transcript segments (the `communication_search` semantic layer).
 *
 * Transcripts are embedded once, at transcription time, and stored as dense vectors keyed by segment
 * id (`transcript_vectors`). This ranker compares a query embedding against a BOUNDED candidate pool
 * of those stored vectors with [TextRetriever.cosine] and returns the best matches, so a query can
 * reach a paraphrase the lexical FTS4 index never indexed — the same fusion idea the long-term memory
 * recall uses ([com.andharness.ru.domain.retrieval.MemoryRanker]).
 *
 * No Android/Room/ONNX dependency: the caller embeds the query and loads the stored vectors; this
 * object only does the vector math, so it is fully unit-testable on the JVM.
 *
 * Cosine references (canonical definitions the implementation follows):
 * - https://en.wikipedia.org/wiki/Cosine_similarity
 * - scikit-learn `cosine_similarity` (the same dot / product-of-norms formula):
 *   https://scikit-learn.org/stable/modules/generated/sklearn.metrics.pairwise.cosine_similarity.html
 */
object TranscriptSemanticRanker {

    /**
     * Rankings are deterministic: equal cosines are ordered by ascending segment id, so the same
     * inputs always produce the same output (the corpus can be re-scanned cheaply without jitter).
     * Only a finite, strictly positive cosine is treated as a hit — a zero/negative or degenerate
     * similarity is not a match.
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

    /**
     * Deduplicates and orders transcript candidates coming from the two independent sources:
     * [semanticHits] (already ranked by [rank]) and [lexicalIds] (FTS order). Semantic hits come
     * first — they are the relevance-ranked source — then the lexical-only ids in their original
     * order; an id present in both appears once, at its best (semantic) position. The result is
     * bounded to [limit], so the merge can never grow the context window.
     */
    fun mergeHits(
        lexicalIds: List<Long>,
        semanticHits: List<Pair<Long, Float>>,
        limit: Int,
    ): List<Long> {
        if (limit <= 0) return emptyList()
        val merged = LinkedHashSet<Long>(lexicalIds.size + semanticHits.size)
        for ((id, _) in semanticHits) {
            if (merged.size >= limit) return merged.toList()
            merged += id
        }
        for (id in lexicalIds) {
            if (merged.size >= limit) break
            merged += id
        }
        return merged.toList()
    }
}
