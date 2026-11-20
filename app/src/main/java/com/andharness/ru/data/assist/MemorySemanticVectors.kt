package com.andharness.ru.data.assist

import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.retrieval.SemanticSearchGate
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort semantic vectors for recall: the query vector plus the candidate document vectors, for
 * fusing BM25 with cosine similarity. Returns `(null, emptyMap())` — a no-op for [MemoryRanker] —
 * whenever the embedder/vector store/settings are unavailable, the `semanticSearchEnabled` switch is
 * off, the query is empty, no vectors for the built-in model are stored, or embedding fails/times
 * out, so recall degrades honestly to exactly the previous BM25 ranking.
 */
internal class MemorySemanticVectors(
    private val memoryVectorDao: MemoryVectorDao?,
    private val localEmbedder: LocalEmbedder?,
    private val settingsStore: LlmSettingsStore?
) {

    /**
     * Встроенный офлайн-эмбеддер запускается, только когда он реально нужен. Эмбеддинг запроса
     * ограничен [EMBED_QUERY_TIMEOUT_MS]: при таймауте/ошибке — честная деградация до BM25, а не
     * исключение. Векторы под другую модель игнорируются (устарели).
     */
    suspend fun vectors(
        query: String,
        memories: List<NoteEntity>
    ): Pair<FloatArray?, Map<String, FloatArray?>> {
        val vectorDao = memoryVectorDao ?: return null to emptyMap()
        val embedder = localEmbedder ?: return null to emptyMap()
        val store = settingsStore ?: return null to emptyMap()
        val enabled = store.config.firstOrNull()?.semanticSearchEnabled == true
        if (!SemanticSearchGate.shouldEmbed(enabled = enabled, text = query)) {
            return null to emptyMap()
        }
        val stored = runCatching { vectorDao.byIds(memories.map { it.id }) }
            .getOrDefault(emptyList())
            .filter { it.model == LocalEmbedder.MODEL_ID }
            .associateBy { it.memoryId }
        if (stored.isEmpty()) return null to emptyMap()
        val queryVector = withTimeoutOrNull(EMBED_QUERY_TIMEOUT_MS) {
            embedder.embed(listOf(query)).getOrNull()?.firstOrNull()
        } ?: return null to emptyMap()
        val docVectors = memories.associate { memory ->
            memory.id.toString() to stored[memory.id]?.let { VectorCodec.decode(it.vector) }
        }
        return queryVector to docVectors
    }

    private companion object {
        /**
         * Жёсткий бюджет на эмбеддинг recall-запроса. Семантика — необязательное улучшение
         * ранжирования, поэтому она никогда не задерживает ответ: по таймауту recall мгновенно
         * откатывается к BM25.
         */
        const val EMBED_QUERY_TIMEOUT_MS = 5_000L
    }
}
