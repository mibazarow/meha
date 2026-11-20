package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw voice embedding sample belonging to a speaker (table `speaker_voice_embeddings`).
 *
 * [SpeakerEntity.centroid] is the running mean; these are the individual samples it was built from,
 * kept so the centroid can be recomputed after a model change or when a misassigned sample is
 * removed. [recordingId] is a soft link to `recordings.id` recording where the sample came from
 * (`null` when it was captured without an owning recording); [quality] is an optional `[0, 1]`
 * score the extractor assigns to the sample.
 *
 * Official reference:
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "speaker_voice_embeddings",
    indices = [
        Index("speakerId"),
        Index("model")
    ]
)
data class SpeakerVoiceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Owning speaker (`speakers.id`). */
    val speakerId: Long,
    /** Recording the sample was extracted from, when known (`recordings.id`). */
    val recordingId: Long?,
    /** Element count of [vector]. */
    val dim: Int,
    /** Little-endian float32 `BLOB` of the voice embedding. */
    val vector: ByteArray,
    /** Embedding model that produced [vector]. */
    val model: String,
    /** Extractor's quality score in `[0, 1]`, when reported. */
    val quality: Float?,
    /** Epoch millis when the sample was stored. */
    val createdAt: Long
)
