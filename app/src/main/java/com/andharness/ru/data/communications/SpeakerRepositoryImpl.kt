package com.andharness.ru.data.communications

import com.andharness.ru.data.db.dao.SpeakerDao
import com.andharness.ru.data.db.dao.SpeakerVoiceEmbeddingDao
import com.andharness.ru.domain.communications.Speaker
import com.andharness.ru.domain.communications.SpeakerRepository
import com.andharness.ru.domain.communications.SpeakerVoiceEmbedding
import com.andharness.ru.domain.retrieval.VectorCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [SpeakerRepository] over `speakers` and `speaker_voice_embeddings`.
 *
 * Voice vectors cross this boundary as dense [FloatArray]s and are stored as little-endian float32
 * `BLOB`s via [VectorCodec].
 *
 * Reference: https://developer.android.com/training/data-storage/room/accessing-data
 */
@Singleton
class SpeakerRepositoryImpl @Inject constructor(
    private val speakerDao: SpeakerDao,
    private val embeddingDao: SpeakerVoiceEmbeddingDao
) : SpeakerRepository {

    override suspend fun list(): List<Speaker> = withContext(Dispatchers.IO) {
        speakerDao.list().map { it.toDomain() }
    }

    override suspend fun getById(id: Long): Speaker? = withContext(Dispatchers.IO) {
        speakerDao.get(id)?.toDomain()
    }

    override suspend fun upsert(speaker: Speaker): Long = withContext(Dispatchers.IO) {
        speakerDao.upsertReturningId(speaker.toEntity())
    }

    override suspend fun linkToPerson(speakerId: Long, personId: Long?): Int = withContext(Dispatchers.IO) {
        speakerDao.linkToPerson(speakerId, personId, System.currentTimeMillis())
    }

    override suspend fun setName(speakerId: Long, name: String?): Int = withContext(Dispatchers.IO) {
        speakerDao.setName(speakerId, name, System.currentTimeMillis())
    }

    override suspend fun setCentroid(
        speakerId: Long,
        centroid: FloatArray?,
        model: String?,
        dim: Int?
    ): Int = withContext(Dispatchers.IO) {
        speakerDao.setCentroidVector(
            id = speakerId,
            centroid = centroid?.let { VectorCodec.encode(it) },
            voiceDim = dim,
            voiceModel = model,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun setCentroidAndCount(
        speakerId: Long,
        centroid: FloatArray?,
        model: String?,
        dim: Int?,
        sampleCount: Int
    ): Int = withContext(Dispatchers.IO) {
        speakerDao.setCentroid(
            id = speakerId,
            centroid = centroid?.let { VectorCodec.encode(it) },
            voiceDim = dim,
            voiceModel = model,
            sampleCount = sampleCount,
            confidence = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun listEmbeddings(speakerId: Long): List<SpeakerVoiceEmbedding> =
        withContext(Dispatchers.IO) {
            embeddingDao.bySpeaker(speakerId).map { it.toDomain() }
        }

    override suspend fun addEmbedding(embedding: SpeakerVoiceEmbedding): Long =
        withContext(Dispatchers.IO) {
            embeddingDao.insert(embedding.copy(id = 0).toEntity())
        }

    override suspend fun deleteEmbeddingsForSpeaker(speakerId: Long): Int = withContext(Dispatchers.IO) {
        embeddingDao.deleteBySpeaker(speakerId)
    }

    override suspend fun delete(speakerId: Long): Int = withContext(Dispatchers.IO) {
        speakerDao.delete(speakerId)
    }
}
