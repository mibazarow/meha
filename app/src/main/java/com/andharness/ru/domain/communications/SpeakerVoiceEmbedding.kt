package com.andharness.ru.domain.communications

/**
 * One raw voice embedding sample belonging to a [Speaker].
 *
 * [Speaker.centroid] is the running mean; these samples are the individual vectors it was built
 * from, kept so the centroid can be recomputed after a model change or when a misassigned sample is
 * removed. [recordingId] records where the sample came from (when known) and [quality] is an
 * optional `[0, 1]` extractor score.
 *
 * Android-free on purpose; the vector is stored as a little-endian float32 `BLOB` by the data layer
 * (see [com.andharness.ru.domain.retrieval.VectorCodec]).
 */
data class SpeakerVoiceEmbedding(
    val id: Long,
    val speakerId: Long,
    val recordingId: Long?,
    val dim: Int,
    val vector: FloatArray,
    val model: String,
    val quality: Float?,
    val createdAt: Long
)
