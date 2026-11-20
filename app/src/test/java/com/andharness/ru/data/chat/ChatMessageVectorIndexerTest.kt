package com.andharness.ru.data.chat

import com.andharness.ru.data.db.dao.ChatMessageVectorDao
import com.andharness.ru.data.db.entity.ChatMessageVectorEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.data.embedding.ChatTextEmbedder
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the chat-message embedding indexer and its [ChatMessageEmbeddingPlan]. The
 * ONNX model is replaced by a [FakeEmbedder] and Room by an in-memory [FakeVectorDao], so the
 * batching, skip rules and idempotent upserts are exercised without a device or the ~29 MB session.
 */
class ChatMessageVectorIndexerTest {

    // ---- ChatMessageEmbeddingPlan (pure) ----

    @Test
    fun planIndexesOnlyVisibleRolesWithLongEnoughBodies() {
        assertTrue(ChatMessageEmbeddingPlan.shouldIndex("user", "How do I reset the router?", true))
        assertTrue(ChatMessageEmbeddingPlan.shouldIndex("assistant", "  a real answer  ", true))
        assertFalse("tool rows are never rendered or searched", ChatMessageEmbeddingPlan.shouldIndex("tool", "a real tool result", true))
        assertFalse(ChatMessageEmbeddingPlan.shouldIndex("system", "a real system note", true))
        assertFalse("short bodies carry too little meaning", ChatMessageEmbeddingPlan.shouldIndex("user", "hi", true))
        assertFalse(ChatMessageEmbeddingPlan.shouldIndex("user", "   ", true))
        assertFalse(ChatMessageEmbeddingPlan.shouldIndex("assistant", "", true))
    }

    @Test
    fun planIsOffWhenSemanticSearchIsDisabled() {
        assertFalse(ChatMessageEmbeddingPlan.shouldIndex("user", "a long enough message", false))
    }

    // ---- embedOne ----

    @Test
    fun embedOneStoresAVectorForAFinalUserMessage() = runTest {
        val dao = FakeVectorDao().apply { messages[1] = msg(1, "user", "How do I reset the router?") }
        val indexer = indexer(dao)

        assertTrue(indexer.embedOne(1))

        assertEquals(1, dao.count())
        val row = dao.get(1)!!
        assertEquals(3, row.dim)
        assertEquals(LocalEmbedder.MODEL_ID, row.model)
    }

    @Test
    fun embedOneIsIdempotent() = runTest {
        val dao = FakeVectorDao().apply { messages[1] = msg(1, "user", "a long enough message") }
        val indexer = indexer(dao)

        assertTrue(indexer.embedOne(1))
        assertTrue(indexer.embedOne(1))

        assertEquals(1, dao.count())
    }

    @Test
    fun embedOneIsNoOpWhenSemanticSearchIsOff() = runTest {
        val dao = FakeVectorDao().apply { messages[1] = msg(1, "user", "a long enough message") }

        assertFalse(indexer(dao, enabled = false).embedOne(1))
        assertEquals(0, dao.count())
    }

    @Test
    fun embedOneIsNoOpForToolRowsShortBodiesAndMissingIds() = runTest {
        val dao = FakeVectorDao().apply {
            messages[1] = msg(1, "tool", "a long enough tool result")
            messages[2] = msg(2, "user", "hi")
        }
        val indexer = indexer(dao)

        assertFalse(indexer.embedOne(1))
        assertFalse(indexer.embedOne(2))
        assertFalse("missing message", indexer.embedOne(42))
        assertEquals(0, dao.count())
    }

    // ---- embedMissing / batching ----

    @Test
    fun embedMissingBatchesCandidatesInChunksOfEight() = runTest {
        val dao = FakeVectorDao().apply {
            (1L..10L).forEach { messages[it] = msg(it, "user", "message number $it") }
        }
        val embedder = FakeEmbedder()
        val indexer = indexer(dao, embedder)

        assertEquals(10, indexer.embedMissing(64))

        assertEquals(listOf(8, 2), embedder.batches.map { it.size })
        assertEquals(10, dao.count())
    }

    @Test
    fun embedMissingSkipsShortBodiesAndToolRowsThenIsIdempotent() = runTest {
        val dao = FakeVectorDao().apply {
            messages[1] = msg(1, "user", "hi")
            messages[2] = msg(2, "assistant", "a proper answer")
            messages[3] = msg(3, "tool", "a long tool body")
        }
        val embedder = FakeEmbedder()
        val indexer = indexer(dao, embedder)

        assertEquals(1, indexer.embedMissing(64))
        assertEquals(1, dao.count())

        val callsAfterFirst = embedder.batches.size
        assertEquals("nothing left to embed on the second pass", 0, indexer.embedMissing(64))
        assertEquals(callsAfterFirst, embedder.batches.size)
    }

    @Test
    fun embedMissingIsNoOpWhenSemanticSearchIsOff() = runTest {
        val dao = FakeVectorDao().apply { messages[1] = msg(1, "user", "a long enough message") }
        assertEquals(0, indexer(dao, enabled = false).embedMissing(64))
    }

    @Test
    fun aFailedBatchDoesNotAbortTheRunAndTheNextCallRetries() = runTest {
        val dao = FakeVectorDao().apply {
            (1L..2L).forEach { messages[it] = msg(it, "user", "message number $it") }
        }
        val embedder = FakeEmbedder().apply { failNext = true }
        val indexer = indexer(dao, embedder)

        assertEquals(0, indexer.embedMissing(64))
        assertEquals(0, dao.count())

        assertEquals(2, indexer.embedMissing(64))
        assertEquals(2, dao.count())
    }

    // ---- backfill ----

    @Test
    fun backfillDrainsAReasonablySizedBacklogThenStops() = runTest {
        val dao = FakeVectorDao().apply {
            (1L..5L).forEach { messages[it] = msg(it, "user", "message number $it") }
        }
        val indexer = indexer(dao)

        assertEquals(5, indexer.backfill(16))
        assertEquals(5, dao.count())
        assertEquals("a drained backfill is a no-op", 0, indexer.backfill(16))
    }

    @Test
    fun backfillIsBoundedByMaxBatches() = runTest {
        val dao = FakeVectorDao().apply {
            (1L..(ChatMessageVectorIndexer.BACKFILL_BATCH_SIZE + 1L)).forEach {
                messages[it] = msg(it, "user", "message number $it")
            }
        }
        val indexer = indexer(dao)

        assertEquals(ChatMessageVectorIndexer.BACKFILL_BATCH_SIZE, indexer.backfill(1))
    }

    // ---- indexAsync ----

    @Test
    fun indexAsyncIndexesOnTheProvidedScopeWithoutBlocking() = runTest {
        val dao = FakeVectorDao().apply { messages[7] = msg(7, "assistant", "a final assistant answer") }
        val indexer = indexer(dao, scope = CoroutineScope(Dispatchers.Unconfined))

        indexer.indexAsync(7)

        assertEquals(1, dao.count())
    }

    // ---- helpers + fakes ----

    private fun indexer(
        dao: FakeVectorDao,
        embedder: FakeEmbedder = FakeEmbedder(),
        enabled: Boolean = true,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ) = ChatMessageVectorIndexer(dao, embedder, FakeSettings(enabled), scope)

    private fun msg(id: Long, role: String, content: String) = MessageEntity(
        id = id,
        chatId = 1L,
        role = role,
        content = content,
        toolName = null,
        createdAt = 0L,
    )

    private class FakeEmbedder : ChatTextEmbedder {
        val batches = mutableListOf<List<String>>()
        var failNext = false

        override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
            batches += texts
            if (failNext) {
                failNext = false
                return Result.failure(IllegalStateException("boom"))
            }
            return Result.success(texts.map { FloatArray(DIM) { 1f } })
        }

        private companion object {
            const val DIM = 3
        }
    }

    /** In-memory stand-in for the Room DAO; [upsert] replaces by primary key, like REPLACE. */
    private class FakeVectorDao : ChatMessageVectorDao {
        val messages = LinkedHashMap<Long, MessageEntity>()
        private val vectors = LinkedHashMap<Long, ChatMessageVectorEntity>()

        override suspend fun upsert(entity: ChatMessageVectorEntity) {
            vectors[entity.messageId] = entity
        }

        override suspend fun upsertAll(entities: List<ChatMessageVectorEntity>) {
            entities.forEach { vectors[it.messageId] = it }
        }

        override suspend fun get(messageId: Long): ChatMessageVectorEntity? = vectors[messageId]

        override suspend fun recent(limit: Int): List<ChatMessageVectorEntity> =
            vectors.values.sortedByDescending { it.messageId }.take(limit)

        override suspend fun recentInChat(chatId: Long, limit: Int): List<ChatMessageVectorEntity> =
            vectors.values
                .filter { messages[it.messageId]?.chatId == chatId }
                .sortedByDescending { it.messageId }
                .take(limit)

        override suspend fun missingMessagesForEmbedding(limit: Int, minLength: Int): List<MessageEntity> =
            messages.values
                .filter { it.role in ChatMessageEmbeddingPlan.INDEXED_ROLES }
                .filter { it.content.trim().length >= minLength }
                .filter { it.id !in vectors }
                .sortedBy { it.id }
                .take(limit)

        override suspend fun messageForEmbedding(messageId: Long): MessageEntity? = messages[messageId]

        override suspend fun count(): Int = vectors.size

        override suspend fun deleteByMessage(messageId: Long) {
            vectors.remove(messageId)
        }
    }

    private class FakeSettings(private val enabled: Boolean) : LlmSettingsStore {
        override val config: Flow<LlmSettings> = flowOf(
            LlmSettings(endpoint = "", model = "", semanticSearchEnabled = enabled)
        )

        override suspend fun updateEndpoint(value: String) = Unit
        override suspend fun updateModel(value: String) = Unit
        override suspend fun updateReasoningEffort(value: String) = Unit
        override suspend fun updateContextCompressionTokens(value: Long) = Unit
        override suspend fun getApiKey(): String? = null
        override suspend fun setApiKey(key: String) = Unit
        override suspend fun clearApiKey() = Unit
        override suspend fun getAuthConfig(): LlmAuthConfig = LlmAuthConfig.DEFAULT
        override suspend fun setAuthConfig(config: LlmAuthConfig) = Unit
        override suspend fun reset() = Unit
        override fun isConfigured(): Flow<Boolean> = flowOf(false)
    }
}
