package com.andharness.ru.domain.retrieval


data class MemoryRecord(
    val id: Long,
    val text: String,
    val tags: String = "",
    val kind: String = "memory",
    val importance: Int = 0,
    val lastUsedAt: Long = 0,
    /** Creation time; the decay reference for a memory that has never been recalled. */
    val createdAt: Long = 0,
    /**
     * Number of times recall surfaced this memory. Feeds the recall-frequency prior in [prior]
     * (0 changes nothing, so a never-recalled memory ranks exactly as before).
     */
    val accessCount: Int = 0,
    /**
     * Stance strength (0..1) for `kind = "opinion"` records. Only opinion-shaped records feed the
     * confidence prior (see [prior]); the default 1.0 leaves every non-opinion record's score
     * exactly as before.
     */
    val confidence: Float = 1f
)
