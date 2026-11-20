package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One distinct voice of the user's recordings (table `speakers`): the speaker registry that
 * diarisation fills in and the user can rename / link to a real person afterwards.
 *
 * [isSelf] marks the device owner's voice; [personId] is a soft link to `persons.id` (no foreign
 * key, matching [PersonEntity]'s rationale). The voiceprint itself is summarised by [centroid]
 * (little-endian float32 `BLOB`, see [com.andharness.ru.domain.retrieval.VectorCodec]) with its
 * [voiceDim], the model that produced it ([voiceModel]) and how many samples fed it ([sampleCount]).
 * Per-sample embeddings live in [SpeakerVoiceEmbeddingEntity].
 *
 * Official reference:
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "speakers",
    indices = [
        Index("personId"),
        Index("isSelf")
    ]
)
data class SpeakerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Display name; `null` for an unnamed speaker cluster. */
    val name: String?,
    /** Whether this speaker is the device owner. */
    val isSelf: Boolean,
    /** Soft link to `persons.id` once the user links the voice to a person. */
    val personId: Long?,
    /** Embedding model that produced [centroid]; a change makes the centroid stale. */
    val voiceModel: String?,
    /** Element count of [centroid]; `null` when no centroid has been computed yet. */
    val voiceDim: Int?,
    /** Mean of the speaker's voice embeddings (little-endian float32 `BLOB`). */
    val centroid: ByteArray?,
    /** Number of embeddings merged into [centroid]. */
    val sampleCount: Int,
    /** Confidence in `[0, 1]` of the speaker assignment, when available. */
    val confidence: Float?,
    /** Epoch millis when the row was created. */
    val createdAt: Long,
    /** Epoch millis when the row was last changed. */
    val updatedAt: Long
)
