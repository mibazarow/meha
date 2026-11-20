package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed chunk of a local document (update.md idea #4, "RAG over local files"). Rows are written
 * by [com.andharness.ru.data.rag.RagIndexer] and never store a whole file: only the extracted,
 * chunked text ([text], ~800 chars with overlap) plus the citation coordinates
 * ([sourcePath], [chunkIndex]) and the freshness key ([updatedAt] = source file `lastModified()`).
 *
 * [sizeBytes] is the source file size at index time; it is informational and part of the honest
 * index report. The index on [sourcePath] serves per-source delete/replace and prefix filters.
 *
 * Schema/column names must match [com.andharness.ru.data.db.RagChunkSchemaSql] used by the Room
 * migration. Official reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "rag_chunks",
    indices = [Index("sourcePath")]
)
data class RagChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourcePath: String,
    val sourceType: String,
    val chunkIndex: Int,
    val text: String,
    val updatedAt: Long,
    val sizeBytes: Long
)
