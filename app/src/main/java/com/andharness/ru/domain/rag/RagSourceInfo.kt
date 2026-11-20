package com.andharness.ru.domain.rag


/** A source present in the index, for stats/reporting. */
data class RagSourceInfo(
    val sourcePath: String,
    val chunkCount: Int,
    val mtime: Long
)
