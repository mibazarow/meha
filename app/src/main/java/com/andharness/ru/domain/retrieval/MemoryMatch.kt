package com.andharness.ru.domain.retrieval


data class MemoryMatch(
    val id: Long,
    /** Combined relevance: BM25 + importance/recency priors. Higher is better. */
    val score: Double,
    /** True when the query lexically matched (possibly through a synonym or inflection). */
    val lexicallyMatched: Boolean
)
