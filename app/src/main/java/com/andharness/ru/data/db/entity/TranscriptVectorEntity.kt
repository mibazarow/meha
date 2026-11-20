package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dense embedding of one transcript segment (table `transcript_vectors`), stored next to the lexical
 * FTS index so search can fuse BM25 with cosine similarity when an embedding model is configured.
 *
 * One vector per segment, hence [segmentId] (a `transcript_segments.id`) is the primary key rather
 * than an auto-generated id. [vector] is the little-endian float32 `BLOB` produced by
 * [com.andharness.ru.domain.retrieval.VectorCodec]; [dim] is its element count (cheap integrity
 * check); [model] records which embedding model produced it, so a model change makes the row stale
 * and the background worker re-embeds it.
 *
 * Schema/column names must match [com.andharness.ru.data.db.CommunicationsSchemaSql]. Official
 * reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "transcript_vectors",
    indices = [Index("model")]
)
data class TranscriptVectorEntity(
    @PrimaryKey
    val segmentId: Long,
    val dim: Int,
    val vector: ByteArray,
    val model: String,
    val updatedAt: Long
)
