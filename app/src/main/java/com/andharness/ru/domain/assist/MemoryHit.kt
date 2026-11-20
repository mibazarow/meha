package com.andharness.ru.domain.assist

import com.andharness.ru.data.db.entity.NoteEntity

/** One ranked memory hit: the stored note plus its combined relevance score. */
data class MemoryHit(
    val note: NoteEntity,
    val score: Double,
    /** False for the honest weak fallback (nothing lexically matched; ranked by importance/recency). */
    val lexicallyMatched: Boolean
)
