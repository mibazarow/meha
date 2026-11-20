package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.entity.MemoryVectorEntity
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.embedding.ChatTextEmbedder
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BUGS.md M6: the same fact said in other words must update the existing row instead of adding a
 * twin. The text-based consolidation cannot see it; the embedding can.
 */
class MemorySemanticDedupTest {

    private val memory = NoteEntity(id = 7L, kind = "memory", text = "Арендовал склад на Ленина")

    private class FakeVectorDao(
        private val rows: List<MemoryVectorEntity>
    ) : MemoryVectorDao {
        override suspend fun upsert(entity: MemoryVectorEntity) = Unit
        override suspend fun upsertAll(entities: List<MemoryVectorEntity>) = Unit
        override suspend fun get(memoryId: Long): MemoryVectorEntity? = null
        override suspend fun byIds(memoryIds: List<Long>): List<MemoryVectorEntity> =
            rows.filter { it.memoryId in memoryIds }
        override suspend fun count(): Int = rows.size
        override suspend fun delete(memoryId: Long) = Unit
        override suspend fun deleteAll() = Unit
    }

    private class FakeEmbedder(private val vector: FloatArray) : ChatTextEmbedder {
        override suspend fun embed(texts: List<String>): Result<List<FloatArray>> =
            Result.success(texts.map { vector })
    }

    private class FakeSettings(private val enabled: Boolean) : LlmSettingsStore {
        override val config: Flow<LlmSettings> =
            flowOf(LlmSettings(endpoint = "", model = "m", semanticSearchEnabled = enabled))
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
        override fun isConfigured(): Flow<Boolean> = flowOf(true)
    }

    private fun vectorRow(id: Long, vector: FloatArray) = MemoryVectorEntity(
        memoryId = id,
        dim = vector.size,
        vector = VectorCodec.encode(VectorCodec.normalize(vector)),
        model = LocalEmbedder.MODEL_ID,
        updatedAt = 0L
    )

    @Test
    fun aParaphraseWithHighCosineIsTheSameFact() = runTest {
        val dedup = MemorySemanticDedup(
            FakeVectorDao(listOf(vectorRow(7L, floatArrayOf(1f, 0f, 0f)))),
            FakeEmbedder(floatArrayOf(0.98f, 0.05f, 0f)),
            FakeSettings(enabled = true)
        )

        assertEquals(7L, dedup.duplicateOf("Снял помещение под хранение товаров", listOf(memory)))
    }

    @Test
    fun aRelatedButDifferentFactIsNotADuplicate() = runTest {
        val dedup = MemorySemanticDedup(
            FakeVectorDao(listOf(vectorRow(7L, floatArrayOf(1f, 0f, 0f)))),
            FakeEmbedder(floatArrayOf(0.7f, 0.7f, 0f)), // cosine ≈ 0.71
            FakeSettings(enabled = true)
        )

        assertNull(dedup.duplicateOf("Арендовал офис в центре", listOf(memory)))
    }

    @Test
    fun withSemanticSearchOffNothingIsMerged() = runTest {
        val dedup = MemorySemanticDedup(
            FakeVectorDao(listOf(vectorRow(7L, floatArrayOf(1f, 0f, 0f)))),
            FakeEmbedder(floatArrayOf(1f, 0f, 0f)),
            FakeSettings(enabled = false)
        )

        assertNull(dedup.duplicateOf("Снял помещение под хранение товаров", listOf(memory)))
    }

    @Test
    fun withoutStoredVectorsNothingIsMerged() = runTest {
        val dedup = MemorySemanticDedup(
            FakeVectorDao(emptyList()),
            FakeEmbedder(floatArrayOf(1f, 0f, 0f)),
            FakeSettings(enabled = true)
        )

        assertNull(dedup.duplicateOf("Снял помещение под хранение товаров", listOf(memory)))
    }

    @Test
    fun withoutAnEmbedderNothingIsMerged() = runTest {
        val dedup = MemorySemanticDedup(
            FakeVectorDao(listOf(vectorRow(7L, floatArrayOf(1f, 0f, 0f)))),
            embedder = null,
            settingsStore = FakeSettings(enabled = true)
        )

        assertNull(dedup.duplicateOf("Снял помещение под хранение товаров", listOf(memory)))
    }
}
