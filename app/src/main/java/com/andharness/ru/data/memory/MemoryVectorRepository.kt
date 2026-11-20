package com.andharness.ru.data.memory

import android.util.Log
import com.andharness.ru.data.db.dao.AssistDao
import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.entity.MemoryVectorEntity
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.retrieval.SemanticSearchGate
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the `memory_vectors` table in sync with long-term memory: finds memories
 * that have no vector yet — or whose vector was produced by a different model tag (stale) — and
 * stores a fresh, L2-normalised vector computed by the bundled on-device [LocalEmbedder].
 *
 * Это полностью офлайн: никакого сетевого вызова и никакого провайдера, только встроенная модель
 * `rubert-tiny-turbo-int8` (dim 312). Работа ограничена и best-effort: не более одного прогона модели на
 * маленький батч, при выключенном [LlmSettingsStore] `semanticSearchEnabled` не делается ничего,
 * при ошибке ничего не удаляется, исключение наружу не пробрасывается — recall в любом случае
 * продолжает работать лексически.
 *
 * References:
 * - Встроенная модель/движок: [LocalEmbedder] (ONNX Runtime, assets/models/embedding).
 * - WorkManager periodic work that drives this: [com.andharness.ru.bg.MemoryEmbeddingWorker].
 */
@Singleton
class MemoryVectorRepository @Inject constructor(
    private val assistDao: AssistDao,
    private val vectorDao: MemoryVectorDao,
    private val localEmbedder: LocalEmbedder,
    private val settingsStore: LlmSettingsStore,
) {

    /**
     * Embeds up to [limit] memories that are missing a vector or whose vector is stale for the
     * bundled model. Returns how many vectors were actually written (0 when semantic search is off,
     * nothing is missing, or every batch failed).
     */
    suspend fun embedMissing(limit: Int): Int {
        val settings = settingsStore.config.firstOrNull() ?: return 0
        if (!settings.semanticSearchEnabled) return 0

        val memories = assistDao.listNotes(KIND_MEMORY, SCAN_LIMIT)
        if (memories.isEmpty()) return 0
        val existing = vectorDao.byIds(memories.map { it.id }).associateBy { it.memoryId }
        val stale = memories
            .filter { existing[it.id]?.model != LocalEmbedder.MODEL_ID }
            .filter { SemanticSearchGate.shouldEmbed(enabled = true, text = embeddingText(it)) }
            .take(limit)
        if (stale.isEmpty()) return 0

        var stored = 0
        for (batch in stale.chunked(EMBED_BATCH_SIZE)) {
            // A single failed batch must not abort the run: the next run retries the same rows.
            val vectors = localEmbedder.embed(batch.map(::embeddingText)).getOrNull() ?: continue
            val now = System.currentTimeMillis()
            val rows = ArrayList<MemoryVectorEntity>()
            batch.forEachIndexed { index, memory ->
                val vector = vectors.getOrNull(index) ?: return@forEachIndexed
                if (vector.isEmpty()) return@forEachIndexed
                val normalized = VectorCodec.normalize(vector)
                if (normalized.isEmpty()) return@forEachIndexed
                rows += MemoryVectorEntity(
                    memoryId = memory.id,
                    dim = normalized.size,
                    vector = VectorCodec.encode(normalized),
                    model = LocalEmbedder.MODEL_ID,
                    updatedAt = now
                )
            }
            if (rows.isNotEmpty()) {
                runCatching { vectorDao.upsertAll(rows) }
                    .onFailure { Log.w(TAG, "Не удалось записать эмбеддинги", it) }
                    .onSuccess { stored += rows.size }
            }
        }
        return stored
    }

    /**
     * Best-effort immediate embedding of ONE just-saved memory (body + tags), so it becomes
     * semantically searchable at once instead of waiting for the periodic worker. Returns true when a
     * fresh vector was stored. No-op (false) when semantic search is off, the id/text is blank, or the
     * model call fails; never throws.
     */
    suspend fun embedOne(memoryId: Long, text: String, tags: String = ""): Boolean {
        if (memoryId <= 0L) return false
        val settings = runCatching { settingsStore.config.firstOrNull() }.getOrNull() ?: return false
        if (!settings.semanticSearchEnabled) return false
        val payload = listOf(text.trim(), tags.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (!SemanticSearchGate.shouldEmbed(enabled = true, text = payload)) return false
        val vector = runCatching {
            withTimeoutOrNull(EMBED_ONE_TIMEOUT_MS) { localEmbedder.embed(listOf(payload)) }
        }.getOrNull()?.getOrNull()?.firstOrNull() ?: return false
        if (vector.isEmpty()) return false
        val normalized = VectorCodec.normalize(vector)
        if (normalized.isEmpty()) return false
        return runCatching {
            vectorDao.upsert(
                MemoryVectorEntity(
                    memoryId = memoryId,
                    dim = normalized.size,
                    vector = VectorCodec.encode(normalized),
                    model = LocalEmbedder.MODEL_ID,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }.onFailure {
            Log.w(TAG, "Не удалось сохранить эмбеддинг памяти", it)
        }.isSuccess
    }

    /** Text embedded for a memory: its body plus the explicit tags, matching what recall matches on. */
    private fun embeddingText(memory: NoteEntity): String =
        listOf(memory.text.trim(), memory.tags.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private companion object {
        const val TAG = "Andharness"
        const val KIND_MEMORY = "memory"

        /** Bounded scan, same order of magnitude as recall's candidate scan. */
        const val SCAN_LIMIT = 500

        /** Small batch (8): bounds one on-device model run so the worker stays light on CPU. */
        const val EMBED_BATCH_SIZE = 8

        /**
         * Bounds the save-time inference. The model is warmed at startup, so this is normally tens of
         * ms; the cap only protects against a stuck/failed run.
         */
        const val EMBED_ONE_TIMEOUT_MS = 3000L
    }
}
