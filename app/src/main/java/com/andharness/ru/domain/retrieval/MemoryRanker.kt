package com.andharness.ru.domain.retrieval

import com.andharness.ru.domain.memory.OpinionConfidence
import kotlin.math.min
import kotlin.math.pow

/**
 * Meaning-based long-term memory recall (update.md idea #3) on top of [TextRetriever].
 *
 * Scores a memory by Okapi BM25 over its text, tags (weighted, because tags are explicit synonyms)
 * and kind, after the shared tokenizer has expanded inflections and synonyms — so «как я решил
 * вопрос с налоговой» reaches a stored «ФНС, декларация» memory. Small importance, recency and
 * recall-frequency priors are added on top of the lexical score; the recency prior decays
 * exponentially with age (see [recencyBonus]) instead of falling off a cliff at a fixed window,
 * while the frequency prior grows by [ACCESS_BONUS_PER_HIT] per recall up to [MAX_ACCESS_BONUS].
 *
 * Honest scope: the lexical+synonym layer is always present; on top of it [rank] fuses BM25 with a
 * real on-device embedding cosine when a query vector and at least one candidate vector are supplied,
 * and degrades to pure BM25 when none are (see [fuseHits] / [TextRetriever.fuse]).
 *
 * Pure Kotlin (no Room/Android) so it is unit-testable on the JVM.
 */

object MemoryRanker {

    /** Tags are explicit recall hints, so their terms are weighted (field boost) above the text. */
    const val TAG_BOOST = 2.0
    const val IMPORTANCE_BONUS = 0.75
    /**
     * Modest additive bonus for memories reached only through the memory↔person graph. It is
     * deliberately weaker than a strong lexical match (so a real hit still wins) but large enough
     * to surface a connected memory that the query does not lexically match at all.
     */
    const val GRAPH_BONUS = 0.6
    /** Recency prior at age zero; it halves every [RECENCY_HALF_LIFE_MS]. */
    const val RECENCY_BONUS = 0.5
    /**
     * Fusion weights when embeddings are available: lexical BM25 keeps the majority
     * weight so semantic similarity only REORDERS genuine lexical matches, never invents them, while
     * the semantic term still carries enough weight to fix synonym/paraphrase ordering.
     */
    const val FUSION_LEXICAL_WEIGHT = 0.6
    const val FUSION_EMBEDDING_WEIGHT = 0.4
    /**
     * Sanity floor for the BEST query↔memory cosine, re-tuned for the bundled
     * `rubert-tiny-turbo-int8` embedder (dim 312). Fresh on-device measurements (5 memories) show
     * the model still has an elevated baseline on short Russian queries: correct paraphrases peak at
     * 0.737–0.809 («снял помещение под хранение» 0.777, «арендовал помещение для товаров» 0.809,
     * «налоги, надо доплатить» 0.737, «приём у врача по зубам» 0.792), while unrelated queries peak
     * at 0.678–0.719 («железнодорожные билеты в Казань» 0.678, «рецепт борща со свёклой» 0.719).
     * A floor of ~0.72 separates this sample. This constant is therefore only a floor: a query whose
     * best candidate cosine is below it is never expanded at all, while the peakiness gate
     * [SEMANTIC_RECALL_MIN_LIFT] is the secondary guard. It is a pragmatic, recall-oriented trade-off
     * — the margin between the two observed ranges is thin, so a marginally related memory may
     * surface, but such a memory is ranked below lexical matches and capped at 5. This is a TUNABLE —
     * raise it to reject more weak-top queries, lower it to attempt recall more eagerly.
     */
    const val SEMANTIC_RECALL_MIN_COSINE = 0.72
    /**
     * Peakiness (LIFT) gate for the semantic layer, and its SECONDARY gate: the TOP query↔memory
     * cosine must exceed the MEAN cosine over all candidate vectors by at least this much. Measured
     * on-device with `rubert-tiny-turbo-int8`, correct paraphrases lift by 0.074–0.122 (top − mean =
     * 0.079 / 0.092 / 0.074 / 0.122) while unrelated queries lift by 0.037–0.085 (0.085 / 0.037).
     * The two ranges OVERLAP, so lift can no longer separate paraphrases from unrelated queries (the
     * previous `rubert-tiny2-int8` model separated them cleanly at 0.10); it is kept as a weak guard
     * that rejects a perfectly flat distribution, while the absolute floor
     * [SEMANTIC_RECALL_MIN_COSINE] does the real separation. When only one candidate has a vector
     * there is no meaningful mean/lift, so the floor alone decides (see [rank]). This is a TUNABLE.
     */
    const val SEMANTIC_RECALL_MIN_LIFT = 0.06
    /**
     * Relative selection margin: once a query has cleared BOTH gates ([SEMANTIC_RECALL_MIN_COSINE]
     * and [SEMANTIC_RECALL_MIN_LIFT]), memories whose cosine is within this margin of the TOP cosine
     * are treated as a semantic match. This recalls a whole cluster of related memories together
     * instead of only the single best one, which matters because the tiny embedder's absolute
     * cosines are not well calibrated across queries. This is a TUNABLE.
     */
    const val SEMANTIC_RECALL_MARGIN = 0.08
    /**
     * Weight applied to the cosine when a memory is recalled ONLY semantically (no lexical and no
     * graph hit). Because [rank] adds it on top of the priors instead of the fused lexical score,
     * keeping it below 1.0 keeps a semantic-only recall ranked BELOW a strong fused lexical match,
     * which never invents a stronger match than the evidence warrants.
     */
    const val SEMANTIC_ONLY_WEIGHT = 0.8
    /**
     * Cap on how many semantic-only memories [rank] may add to the candidate set: this bounds the
     * false-positive surface so a broad cosine can never flood the result with weakly related notes.
     */
    private const val MAX_SEMANTIC_ONLY = 5
    /** Half-life of the exponential recency decay: 30 days. */
    const val RECENCY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000
    /**
     * Additive recall-frequency prior: each surfaced recall ([MemoryRecord.accessCount]) adds this
     * small bonus, so a repeatedly useful memory nudges ahead of an otherwise identical cold one.
     */
    const val ACCESS_BONUS_PER_HIT = 0.1
    /**
     * Cap on the recall-frequency prior: the bonus saturates after
     * `MAX_ACCESS_BONUS / ACCESS_BONUS_PER_HIT` = 5 recalls so a hot memory can never dominate the
     * lexical score purely by being surfaced often.
     */
    const val MAX_ACCESS_BONUS = 0.5
    /**
     * Weight of the confidence prior for opinion-shaped records (`kind = "opinion"`): the prior is
     * `CONFIDENCE_WEIGHT * effectiveConfidence`, so it adds at most this much for a fresh
     * confidence-1.0 opinion and decays with the opinion's age. It never applies to non-opinion
     * records, whose default `confidence = 1.0` therefore changes nothing.
     */
    const val CONFIDENCE_WEIGHT = 0.4
    /** `notes.kind` of an opinion row (see `opinion_set`). */
    const val KIND_OPINION = "opinion"

    /**
     * Exponential recency prior: [RECENCY_BONUS] at age 0, halving every [RECENCY_HALF_LIFE_MS]
     * (30 days). The reference is the last recall ([lastUsedAt]), or [createdAt] before the first
     * recall; an unknown timestamp (0) counts as fresh. Unlike the old 7-day binary window this
     * never drops to zero, so an older memory is ranked lower instead of losing its prior entirely.
     */
    fun recencyBonus(lastUsedAt: Long, createdAt: Long, now: Long): Double {
        val reference = if (lastUsedAt > 0L) lastUsedAt else createdAt
        if (reference <= 0L) return RECENCY_BONUS
        val age = (now - reference).coerceAtLeast(0L)
        return RECENCY_BONUS * 0.5.pow(age.toDouble() / RECENCY_HALF_LIFE_MS)
    }

    /**
     * Ranks [memories] for [query], best first, up to [limit].
     *
     * The candidate set is the UNION of three sources: lexical BM25 hits (synonym/inflection
     * tolerant), [graphIds] memories reached through the memory↔person graph, and — only when
     * [queryVector] and per-memory [docVectors] are supplied — memories selected RELATIVELY by the
     * semantic layer. That layer applies TWO gates. First a sanity FLOOR: the BEST cosine over the
     * candidates must reach [SEMANTIC_RECALL_MIN_COSINE] (0.72 for the bundled `rubert-tiny-turbo-int8`,
     * whose short-Russian-query baseline is elevated: measured paraphrases peak at 0.74–0.81 while
     * unrelated queries peak at 0.68–0.72). Then a secondary PEAKINESS gate: the top cosine must
     * exceed the MEAN cosine over all candidates by at least [SEMANTIC_RECALL_MIN_LIFT] (0.06; the
     * old model's 0.10 cleanly separated paraphrases from unrelated queries, but the new model's lift
     * ranges overlap, so this is only a weak guard against a flat distribution). With one candidate
     * there is no meaningful mean, so the floor alone decides. Once both gates pass, every memory
     * whose cosine is within [SEMANTIC_RECALL_MARGIN] of the top cosine is recalled (capped at 5 by
     * `MAX_SEMANTIC_ONLY`), so a related cluster comes back together instead of only the single best
     * one. This is deliberately recall-oriented: a marginally related memory may surface, but it is
     * ranked below lexical matches and capped at 5. Fusion of BM25 with cosine happens in [fuseHits];
     * the graph and semantic-only unions are applied HERE, in [rank]. This is what lets a paraphrase
     * with ZERO lexical overlap still be recalled.
     *
     * A positive BM25 score is never filtered out: a single overlap (even via a synonym or the
     * 4-char inflection prefix) is a real, if weak, match and is ranked low instead of dropped.
     * Graph-reached memories add [GRAPH_BONUS]; a memory recalled ONLY semantically (no lexical and
     * no graph hit) adds `cos * [SEMANTIC_ONLY_WEIGHT]`, deliberately below a strong fused lexical
     * match. Cosine is NOT added twice for a lexical hit — [fuseHits] already fused it. A semantic
     * recall is a genuine recall, so it is reported as [MemoryMatch.lexicallyMatched] = true.
     *
     * When nothing matches at all (e.g. a blank/stop-word-only query), the most important/freshest
     * memories are returned with [MemoryMatch.lexicallyMatched] = false so the caller can label them
     * honestly instead of pretending there was a match.
     *
     * Degradation rule: with [queryVector] = null, all-null [docVectors], a best cosine below
     * [SEMANTIC_RECALL_MIN_COSINE], or insufficient peakiness ([SEMANTIC_RECALL_MIN_LIFT]), the
     * semantic set is empty and the result is byte-for-byte the pre-semantic behaviour. With the
     * default empty [graphIds] too, this method behaves EXACTLY as the pre-graph version as well.
     */
    fun rank(
        query: String,
        memories: List<MemoryRecord>,
        limit: Int = 10,
        now: Long = System.currentTimeMillis(),
        /**
         * Optional query embedding and per-memory vectors. Default null/empty keeps
         * the pure lexical ranking EXACTLY as before: [fuseHits] returns the BM25 hits untouched
         * whenever the query vector is absent or no candidate has a vector.
         */
        queryVector: FloatArray? = null,
        docVectors: Map<String, FloatArray?> = emptyMap(),
        /**
         * Optional graph-reached memory ids (memory↔person expansion). Empty by default, which
         * keeps the ranking byte-for-byte identical to the pre-graph behaviour.
         */
        graphIds: Set<Long> = emptySet(),
        /**
         * True when the turn is a question about the PAST («что мы решили», «помнишь», «раньше»).
         * The age prior is then neutralised ([RECENCY_BONUS] for every memory) so an older
         * agreement is not buried under fresh trivia — the whole point of the question is to
         * reach back. Decided by [com.andharness.ru.domain.agent.RecallIntent], never guessed here.
         * Default false keeps the ranking byte-for-byte identical.
         */
        ignoreRecency: Boolean = false
    ): List<MemoryMatch> {
        if (memories.isEmpty() || limit <= 0) return emptyList()

        val docs = memories.map { doc(it) }
        val hits = fuseHits(
            TextRetriever.bm25(query, docs, limit = Int.MAX_VALUE),
            queryVector,
            docVectors
        )
        val lexicalById = hits.associate { it.id to it.score }

        // Semantic expansion: memories the query does not lexically match but is close to in
        // embedding space. Two gates decide whether to expand at all: an absolute FLOOR on the top
        // cosine (SEMANTIC_RECALL_MIN_COSINE = 0.72) and a secondary PEAKINESS gate — the top cosine
        // must exceed the MEAN cosine over all candidates by SEMANTIC_RECALL_MIN_LIFT (= 0.06).
        // With the bundled `rubert-tiny-turbo-int8` model the lift ranges of paraphrases and
        // unrelated queries overlap, so the absolute floor — not the lift — is what separates them
        // (measured paraphrase peaks 0.74–0.81 vs unrelated peaks 0.68–0.72); the lift gate remains
        // only as a weak guard so a perfectly flat distribution never expands. With a single
        // candidate there is no meaningful mean, so the floor alone decides. Selected memories are
        // then taken RELATIVELY (within SEMANTIC_RECALL_MARGIN of the top), so a related cluster
        // comes back together. Empty unless a query vector and at least one candidate vector exist.
        val semanticById: Map<Long, Double> =
            if (queryVector == null) emptyMap() else {
                val cosines = memories.mapNotNull { m ->
                    val v = docVectors[m.id.toString()] ?: return@mapNotNull null
                    val c = TextRetriever.cosine(queryVector, v)
                    if (c.isFinite()) m.id to c else null
                }
                val top = cosines.maxOfOrNull { it.second } ?: 0.0
                when {
                    cosines.isEmpty() || top < SEMANTIC_RECALL_MIN_COSINE -> emptyMap()
                    // One candidate has no meaningful "lift": fall back to the floor alone.
                    cosines.size == 1 -> mapOf(cosines[0])
                    else -> {
                        val mean = cosines.sumOf { it.second } / cosines.size
                        if (top - mean < SEMANTIC_RECALL_MIN_LIFT) emptyMap()
                        else cosines
                            .filter { it.second >= maxOf(SEMANTIC_RECALL_MIN_COSINE, top - SEMANTIC_RECALL_MARGIN) }
                            .sortedByDescending { it.second }
                            .take(MAX_SEMANTIC_ONLY)
                            .toMap()
                    }
                }
            }

        val matched = memories.filter {
            lexicalById.containsKey(it.id.toString()) || it.id in graphIds ||
                semanticById.containsKey(it.id)
        }
        if (matched.isNotEmpty()) {
            val scored = matched.map {
                val id = it.id.toString()
                val hasLexical = lexicalById.containsKey(id)
                val lexical = lexicalById[id] ?: 0.0
                val graphBonus = if (it.id in graphIds) GRAPH_BONUS else 0.0
                val semanticBonus = if (!hasLexical && semanticById.containsKey(it.id)) {
                    semanticById.getValue(it.id) * SEMANTIC_ONLY_WEIGHT
                } else {
                    0.0
                }
                Scored(
                    match = MemoryMatch(
                        id = it.id,
                        score = lexical + prior(it, now, ignoreRecency) + graphBonus + semanticBonus,
                        lexicallyMatched = hasLexical || it.id in graphIds ||
                            semanticById.containsKey(it.id)
                    ),
                    hasLexical = hasLexical
                )
            }
            // A real lexical match must always beat a memory that is only CONNECTED to one. Without
            // this, a graph-reached notification with importance 2 and a few recalls (1.5 + 0.5 +
            // 0.5 + 0.6 = 3.1) outranks a genuine match (~2.2) and `memory_search` returns noise —
            // measured on a real 900-row database, where «крепкий чай» returned «Теплички: спасибо».
            // Graph-only and semantic-only rows are therefore capped just below the weakest lexical
            // hit; when nothing matches lexically they keep their full score and still surface.
            val lexicalFloor = scored.filter { it.hasLexical }.minOfOrNull { it.match.score }
            val capped = if (lexicalFloor == null) {
                scored.map { it.match }
            } else {
                scored.map {
                    if (it.hasLexical) it.match
                    else it.match.copy(score = minOf(it.match.score, lexicalFloor - FLOOR_EPSILON))
                }
            }
            return capped
                .sortedByDescending { it.score }
                .take(limit)
        }

        return memories
            .map { MemoryMatch(it.id, prior(it, now, ignoreRecency), false) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Fuses BM25 with semantic similarity through the shared [TextRetriever.fuse].
     *
     * Degradation rule: when there is no query vector, no hits, or none of the candidates has a
     * vector, the lexical hits are returned UNCHANGED — recall is byte-for-byte the pre-embedding
     * behaviour. Only when at least one candidate vector exists are the lexical scores first scaled
     * to [0, 1] (a monotonic scaling, so the lexical order is preserved) so that
     * [FUSION_LEXICAL_WEIGHT]/[FUSION_EMBEDDING_WEIGHT] weigh comparable quantities; cosine is
     * already in [-1, 1].
     *
     * Note: this fuser only RERANKS memories BM25 already matched. The candidate SET is expanded
     * OUTSIDE it, in [rank]: the memory↔person graph union with [graphIds], and (when vectors exist)
     * the semantic union of memories selected by the floor + lift gates
     * ([SEMANTIC_RECALL_MIN_COSINE], [SEMANTIC_RECALL_MIN_LIFT]). So a paraphrase with zero lexical
     * overlap can be recalled there even though `fuseHits` never adds a memory itself.
     */
    private fun fuseHits(
        hits: List<TextRetriever.Hit>,
        queryVector: FloatArray?,
        docVectors: Map<String, FloatArray?>
    ): List<TextRetriever.Hit> {
        if (queryVector == null || hits.isEmpty()) return hits
        if (docVectors.values.all { it == null }) return hits
        val max = hits.maxOf { it.score }
        val normalized = if (max > 0.0) hits.map { TextRetriever.Hit(it.id, it.score / max) } else hits
        return TextRetriever.fuse(
            lexical = normalized,
            queryVector = queryVector,
            docVectors = docVectors,
            lexicalWeight = FUSION_LEXICAL_WEIGHT,
            embeddingWeight = FUSION_EMBEDDING_WEIGHT
        )
    }

    private fun prior(memory: MemoryRecord, now: Long, ignoreRecency: Boolean): Double =
        memory.importance.coerceIn(0, 2) * IMPORTANCE_BONUS +
            // A past-recall question turns the age penalty off: every memory gets the full recency
            // prior, so the ranking is decided by relevance, importance and recall frequency alone.
            (if (ignoreRecency) RECENCY_BONUS else recencyBonus(memory.lastUsedAt, memory.createdAt, now)) +
            min(memory.accessCount, 5) * ACCESS_BONUS_PER_HIT +
            confidenceBonus(memory, now)

    /**
     * Confidence prior for opinion-shaped records only: [CONFIDENCE_WEIGHT] scaled by the
     * decay-adjusted confidence at [now]. Gated on `kind == "opinion"` so the default
     * `confidence = 1.0` of every note/memory changes nothing — non-opinion scores stay identical.
     * Retired opinions never reach this method: the repository excludes them from the candidates.
     */
    private fun confidenceBonus(memory: MemoryRecord, now: Long): Double {
        if (memory.kind != KIND_OPINION) return 0.0
        val effective = OpinionConfidence.effective(memory.confidence, memory.createdAt, now)
        return CONFIDENCE_WEIGHT * effective
    }

    /** A scored match plus whether it came from a genuine lexical hit (used for the floor rule). */
    private data class Scored(val match: MemoryMatch, val hasLexical: Boolean)

    /** Keeps a capped row strictly below the floor even when both scores would otherwise be equal. */
    private const val FLOOR_EPSILON = 1e-6

    private fun doc(memory: MemoryRecord): TextRetriever.Doc = TextRetriever.Doc(
        id = memory.id.toString(),
        tokens = TextRetriever.tokenize(memory.text) + TextRetriever.tokenize(memory.kind),
        boostedTokens = TextRetriever.tokenize(memory.tags),
        boost = TAG_BOOST
    )
}
