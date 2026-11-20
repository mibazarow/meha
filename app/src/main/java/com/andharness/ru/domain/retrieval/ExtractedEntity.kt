package com.andharness.ru.domain.retrieval

/**
 * One generic entity detected in a memory. [name] keeps the original casing; [kind] is the entity
 * category; [weight] is the link strength. [normalizedName] is the canonical dedup key.
 */
data class ExtractedEntity(
    val name: String,
    val kind: String,
    val weight: Float
) {
    /** Canonical dedup key (lower-case, single-spaced). */
    val normalizedName: String get() = MemoryEntityExtractor.normalize(name)
}
