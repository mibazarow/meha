package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dense embedding of one long-term memory, stored next to the lexical FTS index so
 * recall can fuse BM25 with cosine similarity when an embedding model is configured.
 *
 * [memoryId] is the `notes.id` of the `kind="memory"` row (one vector per memory, hence the primary
 * key); [vector] is the little-endian float32 `BLOB` produced by
 * [com.andharness.ru.domain.retrieval.VectorCodec]; [dim] is its element count (a cheap integrity
 * check); [model] records which embedding model produced it, so a model change makes the row stale
 * and the background worker re-embeds it; [updatedAt] is the epoch-millis write time.
 *
 * Schema/column names must match [com.andharness.ru.data.db.MemoryVectorSchemaSql] used by the Room
 * migration 28 -> 29. Official reference:
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "memory_vectors",
    indices = [Index("memoryId")]
)
data class MemoryVectorEntity(
    @PrimaryKey
    val memoryId: Long,
    val dim: Int,
    val vector: ByteArray,
    val model: String,
    val updatedAt: Long
)
