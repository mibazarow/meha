package com.andharness.ru.data.communications

import com.andharness.ru.data.db.dao.TranscriptFtsDao
import com.andharness.ru.data.db.dao.TranscriptSegmentDao
import com.andharness.ru.data.db.dao.TranscriptVectorDao
import com.andharness.ru.data.db.entity.TranscriptVectorEntity
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.communications.TranscriptFtsQuery
import com.andharness.ru.domain.communications.TranscriptRepository
import com.andharness.ru.domain.communications.TranscriptSegment
import com.andharness.ru.domain.retrieval.VectorCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [TranscriptRepository] over `transcript_segments`, `transcript_fts` and
 * `transcript_vectors`.
 *
 * Lexical search builds a sanitised FTS4 `MATCH` expression with [TranscriptFtsQuery], so raw user
 * input can never inject FTS operators. Dense embeddings go through [VectorCodec], the same
 * little-endian float32 format the rest of the app uses.
 *
 * Reference: https://developer.android.com/training/data-storage/room/accessing-data
 */
@Singleton
class TranscriptRepositoryImpl @Inject constructor(
    private val segmentDao: TranscriptSegmentDao,
    private val ftsDao: TranscriptFtsDao,
    private val vectorDao: TranscriptVectorDao
) : TranscriptRepository {

    override suspend fun segmentsFor(recordingId: Long): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            segmentDao.byRecording(recordingId).map { it.toDomain() }
        }

    override suspend fun segmentsByIds(ids: List<Long>): List<TranscriptSegment> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            segmentDao.byIds(ids).map { it.toDomain() }
        }

    override suspend fun insertSegments(
        recordingId: Long,
        segments: List<TranscriptSegment>
    ): List<Long> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()
        val entities = segments.map { it.copy(id = 0).toEntity(recordingId) }
        segmentDao.insertAll(entities)
    }

    override suspend fun deleteFor(recordingId: Long): Int = withContext(Dispatchers.IO) {
        segmentDao.deleteForRecording(recordingId)
    }

    override suspend fun assignSpeaker(segmentId: Long, speakerId: Long?): Int =
        withContext(Dispatchers.IO) {
            segmentDao.updateSpeaker(segmentId, speakerId)
        }

    override suspend fun searchFts(query: String, limit: Int): List<Long> = withContext(Dispatchers.IO) {
        val match = TranscriptFtsQuery.build(query)
        if (match.isEmpty()) emptyList() else ftsDao.searchCandidateIds(match, limit)
    }

    override suspend fun putVector(segmentId: Long, vector: FloatArray, model: String) =
        withContext(Dispatchers.IO) {
            vectorDao.upsert(
                TranscriptVectorEntity(
                    segmentId = segmentId,
                    dim = vector.size,
                    vector = VectorCodec.encode(vector),
                    model = model,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    override suspend fun vector(segmentId: Long): FloatArray? = withContext(Dispatchers.IO) {
        vectorDao.get(segmentId)?.let { VectorCodec.decode(it.vector) }
    }

    override suspend fun recentVectors(limit: Int): List<Pair<Long, FloatArray>> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            vectorDao.recent(limit)
                .filter { it.model == LocalEmbedder.MODEL_ID }
                .mapNotNull { entity ->
                    val vector = VectorCodec.decode(entity.vector)
                    if (vector.isEmpty()) null else entity.segmentId to vector
                }
        }

    override suspend fun missingVectorSegmentIds(limit: Int): List<Long> = withContext(Dispatchers.IO) {
        vectorDao.missingAllSegmentIds(limit)
    }
}
