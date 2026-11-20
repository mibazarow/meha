package com.andharness.ru.data.messages

import com.andharness.ru.data.db.dao.StoredMessageDao
import com.andharness.ru.data.db.dao.StoredMessageFtsDao
import com.andharness.ru.data.db.dao.StoredMessageVectorDao
import com.andharness.ru.data.db.entity.StoredMessageVectorEntity
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.messages.StoredMessageFtsQuery
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.retrieval.VectorCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [StoredMessagesRepository] over `stored_messages`, `stored_message_fts` and
 * `stored_message_vectors`.
 *
 * Lexical search builds a sanitised FTS4 `MATCH` expression with [StoredMessageFtsQuery], so raw user
 * input can never inject FTS operators. Dense embeddings go through [VectorCodec], the same
 * little-endian float32 format the rest of the app uses.
 *
 * Reference: https://developer.android.com/training/data-storage/room/accessing-data
 */
@Singleton
class StoredMessagesRepositoryImpl @Inject constructor(
    private val messageDao: StoredMessageDao,
    private val ftsDao: StoredMessageFtsDao,
    private val vectorDao: StoredMessageVectorDao
) : StoredMessagesRepository {

    override suspend fun insert(message: StoredMessage): Long = withContext(Dispatchers.IO) {
        messageDao.insert(message.copy(id = 0).toEntity())
    }

    override suspend fun recent(limit: Int, sourceType: String?, personId: Long?): List<StoredMessage> =
        withContext(Dispatchers.IO) {
            messageDao.recent(limit, sourceType, personId).map { it.toDomain() }
        }

    override suspend fun forConversation(key: String, limit: Int): List<StoredMessage> =
        withContext(Dispatchers.IO) {
            messageDao.forConversation(key, limit).map { it.toDomain() }
        }

    override suspend fun byIds(ids: List<Long>): List<StoredMessage> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        messageDao.byIds(ids).map { it.toDomain() }
    }

    override suspend fun searchFts(query: String, limit: Int): List<StoredMessage> =
        withContext(Dispatchers.IO) {
            val match = StoredMessageFtsQuery.build(query)
            if (match.isEmpty()) return@withContext emptyList()
            val ids = ftsDao.searchCandidateIds(match, limit)
            if (ids.isEmpty()) emptyList() else messageDao.byIds(ids).map { it.toDomain() }
        }

    override suspend fun putVector(messageId: Long, vector: FloatArray, model: String) =
        withContext(Dispatchers.IO) {
            vectorDao.upsert(
                StoredMessageVectorEntity(
                    messageId = messageId,
                    dim = vector.size,
                    vector = VectorCodec.encode(vector),
                    model = model,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    override suspend fun missingVectorIds(limit: Int): List<Long> = withContext(Dispatchers.IO) {
        vectorDao.missingAllMessageIds(limit)
    }

    override suspend fun vector(messageId: Long): FloatArray? = withContext(Dispatchers.IO) {
        vectorDao.get(messageId)?.let { VectorCodec.decode(it.vector) }
    }

    override suspend fun recentVectors(limit: Int): List<Pair<Long, FloatArray>> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            vectorDao.recent(limit)
                .filter { it.model == LocalEmbedder.MODEL_ID }
                .mapNotNull { entity ->
                    val vector = VectorCodec.decode(entity.vector)
                    if (vector.isEmpty()) null else entity.messageId to vector
                }
        }

    override suspend fun deleteForPerson(personId: Long): Int = withContext(Dispatchers.IO) {
        messageDao.deleteByPerson(personId)
    }

    override suspend fun findByExternalRef(ref: String): StoredMessage? = withContext(Dispatchers.IO) {
        messageDao.findByExternalRef(ref)?.toDomain()
    }
}
