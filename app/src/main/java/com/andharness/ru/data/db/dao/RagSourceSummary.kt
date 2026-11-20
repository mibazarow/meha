package com.andharness.ru.data.db.dao


/** Per-source chunk summary used by `rag_index` reports and the read-only DB viewer. */
data class RagSourceSummary(
    val sourcePath: String,
    val chunkCount: Int,
    val mtime: Long
)
