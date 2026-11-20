package com.andharness.ru.domain.communications

/**
 * Persistence for the speaker registry and its raw voice samples (tables `speakers`,
 * `speaker_voice_embeddings`).
 *
 * [setCentroid] takes a dense [FloatArray]; the implementation stores it as a little-endian float32
 * `BLOB` via [com.andharness.ru.domain.retrieval.VectorCodec], keeping the domain Android-free and
 * the storage compact.
 */
interface SpeakerRepository {

    /** All speakers, device owner first, then by creation time. */
    suspend fun list(): List<Speaker>

    suspend fun getById(id: Long): Speaker?

    /** Inserts or replaces a speaker by id and returns its row id. */
    suspend fun upsert(speaker: Speaker): Long

    /** Links (or unlinks, with `null`) a speaker to a person. */
    suspend fun linkToPerson(speakerId: Long, personId: Long?): Int

    suspend fun setName(speakerId: Long, name: String?): Int

    /**
     * Stores a freshly recomputed voice centroid and its provenance. A `null` [centroid] clears the
     * voiceprint; [model]/[dim] describe the vector that produced it.
     */
    suspend fun setCentroid(speakerId: Long, centroid: FloatArray?, model: String?, dim: Int?): Int

    /**
     * Atomically stores a freshly recomputed [centroid] (and its [model]/[dim]) together with the
     * [sampleCount] it now summarises. Diarisation uses this to fold a new cluster into the speaker's
     * running mean without a separate read-modify-write of the count.
     */
    suspend fun setCentroidAndCount(
        speakerId: Long,
        centroid: FloatArray?,
        model: String?,
        dim: Int?,
        sampleCount: Int,
    ): Int

    /** Samples of one speaker, oldest first. */
    suspend fun listEmbeddings(speakerId: Long): List<SpeakerVoiceEmbedding>

    /** Stores one raw voice sample and returns its row id. */
    suspend fun addEmbedding(embedding: SpeakerVoiceEmbedding): Long

    suspend fun deleteEmbeddingsForSpeaker(speakerId: Long): Int

    suspend fun delete(speakerId: Long): Int
}
