package com.andharness.ru.domain.retrieval

/**
 * Consolidation rule for `memory_save`: instead of blindly appending a row,
 * decide whether an incoming memory restates or contradicts an already stored one and, if so, UPDATE
 * that row instead of inserting a duplicate.
 *
 * Pure Kotlin (no Room/Android), deterministic and unit-tested. Similarity is the token Jaccard over
 * [TextRetriever.tokenize] output, so inflections and synonyms are already bridged by the shared
 * tokenizer.
 */
sealed interface MemorySavePlan {
    /** Nothing close enough was found: a new row must be inserted. */
    data class Insert(val text: String, val tags: String, val importance: Int) : MemorySavePlan

    /** A near-duplicate: the NEW text replaces [id]'s content and the tags are unioned. */
    data class Update(
        val id: Long,
        val text: String,
        val tags: String,
        val importance: Int,
        val lastUsedAt: Long
    ) : MemorySavePlan
}
