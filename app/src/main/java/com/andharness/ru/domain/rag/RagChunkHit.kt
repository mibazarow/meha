package com.andharness.ru.domain.rag


/** One ranked chunk returned by [RagRepository.query]; the citations the agent must quote. */
data class RagChunkHit(
    val sourcePath: String,
    val sourceType: String,
    val chunkIndex: Int,
    val text: String,
    val score: Double,
    val updatedAt: Long,
    val sizeBytes: Long
)
