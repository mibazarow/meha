package com.andharness.ru.domain.communications

/**
 * One distinct voice found in the user's recordings: the speaker registry that diarisation fills in
 * and the user can rename / link to a person afterwards.
 *
 * [isSelf] marks the device owner's voice; [personId] is a soft link to a person. The voiceprint is
 * summarised by [centroid] (a dense float vector) together with [voiceDim], the [voiceModel] that
 * produced it, and how many samples fed it ([sampleCount]); the individual samples live in
 * [SpeakerVoiceEmbedding].
 *
 * Android-free on purpose; the centroid is stored as a little-endian float32 `BLOB` by the data
 * layer (see [com.andharness.ru.domain.retrieval.VectorCodec]).
 */
data class Speaker(
    val id: Long,
    val name: String?,
    val isSelf: Boolean,
    val personId: Long?,
    val voiceModel: String?,
    val voiceDim: Int?,
    val centroid: FloatArray?,
    val sampleCount: Int,
    val confidence: Float?,
    val createdAt: Long,
    val updatedAt: Long
)
