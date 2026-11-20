package com.andharness.ru.data.messages

import com.andharness.ru.domain.messages.MessageQueryEmbedder
import com.andharness.ru.domain.messages.MessageSearchHit
import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.messages.StoredMessageDirection
import com.andharness.ru.domain.messages.StoredMessageSource
import com.andharness.ru.domain.messages.StoredMessagesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of [MessageSearchServiceImpl] against a fake [StoredMessagesRepository] and a fake
 * [MessageQueryEmbedder]: lexical-only search, semantic ranking + merge, source/person filters and
 * the honest degradation to lexical when the embedder is unavailable.
 */
class MessageSearchServiceImplTest {

    private val noEmbedder = MessageQueryEmbedder { null }

    @Test
    fun lexicalOnlyReturnsFtsHitsNewestFirst() = runTest {
        val repo = FakeRepo(
            ftsHits = listOf(msg(1, at = 100), msg(2, at = 300)),
        )
        val hits = MessageSearchServiceImpl(repo, noEmbedder)
            .search("привет", limit = 10, semantic = false)

        assertEquals(listOf(2L, 1L), hits.map { it.messageId })
        assertTrue(hits.all { it.score == 0.0 })
    }

    @Test
    fun semanticRanksVectorsAndMergesWithLexicalHits() = runTest {
        val queryVector = floatArrayOf(1f, 0f)
        val repo = FakeRepo(
            ftsHits = listOf(msg(5, at = 50)), // lexical-only tail
            vectors = listOf(
                3L to floatArrayOf(1f, 0f), // cos 1.0
                2L to floatArrayOf(1f, 1f), // cos ~0.707
                1L to floatArrayOf(0f, 1f), // cos 0 -> dropped
            ),
            messages = mapOf(
                1L to msg(1, at = 10),
                2L to msg(2, at = 20),
                3L to msg(3, at = 30),
                5L to msg(5, at = 50),
            ),
        )
        val hits = MessageSearchServiceImpl(repo, MessageQueryEmbedder { queryVector })
            .search("привет", limit = 10, semantic = true)

        assertEquals(listOf(3L, 2L, 5L), hits.map { it.messageId })
        assertEquals(1.0, hits[0].score, 1e-6)
        assertEquals(0.0, hits[2].score, 1e-6)
    }

    @Test
    fun semanticDegradesToLexicalWhenEmbedderUnavailable() = runTest {
        val repo = FakeRepo(
            ftsHits = listOf(msg(1, at = 100)),
            vectors = listOf(2L to floatArrayOf(1f, 0f)),
            messages = mapOf(1L to msg(1, at = 100), 2L to msg(2, at = 200)),
        )
        val hits = MessageSearchServiceImpl(repo, noEmbedder)
            .search("привет", limit = 10, semantic = true)

        // The vector hit (id 2) is NOT visible; only the lexical hit remains.
        assertEquals(listOf(1L), hits.map { it.messageId })
    }

    @Test
    fun sourceAndPersonFiltersApplyToBothSources() = runTest {
        val repo = FakeRepo(
            ftsHits = listOf(
                msg(1, at = 100, source = StoredMessageSource.SMS, personId = 7),
                msg(2, at = 200, source = StoredMessageSource.MESSENGER, personId = 7),
            ),
            vectors = listOf(3L to floatArrayOf(1f, 0f)),
            messages = mapOf(
                1L to msg(1, at = 100, source = StoredMessageSource.SMS, personId = 7),
                2L to msg(2, at = 200, source = StoredMessageSource.MESSENGER, personId = 7),
                3L to msg(3, at = 300, source = StoredMessageSource.SMS, personId = 8),
            ),
        )
        val hits = MessageSearchServiceImpl(repo, MessageQueryEmbedder { floatArrayOf(1f, 0f) })
            .search("привет", limit = 10, source = "sms", personId = 7, semantic = true)

        assertEquals(listOf(1L), hits.map { it.messageId })
    }

    @Test
    fun blankQueryAndNonPositiveLimitYieldNothing() = runTest {
        val repo = FakeRepo(ftsHits = listOf(msg(1)))
        val service = MessageSearchServiceImpl(repo, noEmbedder)
        assertTrue(service.search("   ", limit = 10).isEmpty())
        assertTrue(service.search("привет", limit = 0).isEmpty())
    }

    @Test
    fun fullTextIsPreservedInTheHit() = runTest {
        val body = "Полный текст сообщения, который не должен обрезаться сервисом."
        val repo = FakeRepo(ftsHits = listOf(msg(1, text = body)))
        val hits: List<MessageSearchHit> = MessageSearchServiceImpl(repo, noEmbedder)
            .search("текст", limit = 10, semantic = false)

        assertEquals(body, hits.single().text)
    }

    private fun msg(
        id: Long,
        at: Long = id,
        source: String = StoredMessageSource.SMS,
        personId: Long? = null,
        text: String = "сообщение $id",
    ): StoredMessage = StoredMessage(
        id = id,
        sourceType = source,
        packageName = "com.example",
        conversationKey = "conv-$id",
        personId = personId,
        direction = StoredMessageDirection.INCOMING,
        sender = "Иван",
        isGroup = false,
        at = at,
        text = text,
        externalRef = "ref-$id",
        createdAt = at,
    )

    private class FakeRepo(
        private val ftsHits: List<StoredMessage> = emptyList(),
        private val vectors: List<Pair<Long, FloatArray>> = emptyList(),
        private val messages: Map<Long, StoredMessage> = emptyMap(),
    ) : StoredMessagesRepository {

        override suspend fun insert(message: StoredMessage): Long = 0L

        override suspend fun recent(
            limit: Int,
            sourceType: String?,
            personId: Long?,
        ): List<StoredMessage> = emptyList()

        override suspend fun forConversation(key: String, limit: Int): List<StoredMessage> =
            emptyList()

        override suspend fun byIds(ids: List<Long>): List<StoredMessage> =
            ids.mapNotNull { messages[it] }

        override suspend fun searchFts(query: String, limit: Int): List<StoredMessage> =
            ftsHits.take(limit)

        override suspend fun putVector(messageId: Long, vector: FloatArray, model: String) = Unit

        override suspend fun missingVectorIds(limit: Int): List<Long> = emptyList()

        override suspend fun vector(messageId: Long): FloatArray? =
            vectors.firstOrNull { it.first == messageId }?.second

        override suspend fun recentVectors(limit: Int): List<Pair<Long, FloatArray>> =
            vectors.take(limit)

        override suspend fun deleteForPerson(personId: Long): Int = 0

        override suspend fun findByExternalRef(ref: String): StoredMessage? = null
    }
}
