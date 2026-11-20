package com.andharness.ru.domain.rag


/** Query result: the ranked hits plus the honest size of the search space. */
data class RagQueryResult(
    val hits: List<RagChunkHit>,
    val searchedChunks: Int,
    val note: String? = null
)
