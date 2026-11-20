package com.andharness.ru.data.messages

import android.util.Log
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.retrieval.SemanticSearchGate
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded, best-effort embedder for captured messages: finds `stored_messages` rows without a vector
 * for the bundled model, embeds their FULL text with the offline [LocalEmbedder] and stores the
 * L2-normalised result in `stored_message_vectors`.
 *
 * Mirrors [com.andharness.ru.data.memory.MemoryVectorRepository]: nothing runs while
 * `semanticSearchEnabled` is off, blank / too-short bodies are skipped, batches are small (so one run
 * is cheap on CPU) and a failed batch is logged but never aborts the run or throws — the next
 * periodic run retries the same rows.
 *
 * References:
 * - ONNX Runtime Java API / bundled model: [LocalEmbedder].
 * - Worker driving this: [com.andharness.ru.data.messages.StoredMessageEmbeddingWorker].
 */
@Singleton
class StoredMessageVectorRepository @Inject constructor(
    private val repository: StoredMessagesRepository,
    private val localEmbedder: LocalEmbedder,
    private val settingsStore: LlmSettingsStore,
) {

    /**
     * Embeds up to [limit] messages that still have no vector. Returns how many vectors were actually
     * written (0 when semantic search is off, nothing is missing, or every batch failed).
     */
    suspend fun embedMissing(limit: Int): Int {
        if (limit <= 0) return 0
        val enabled = runCatching {
            settingsStore.config.firstOrNull()?.semanticSearchEnabled == true
        }.getOrDefault(false)
        if (!enabled) return 0

        val ids = repository.missingVectorIds(limit)
        if (ids.isEmpty()) return 0
        val messagesById = runCatching { repository.byIds(ids) }.getOrDefault(emptyList())
            .associateBy { it.id }
        val targets = ids.mapNotNull { id ->
            val message = messagesById[id] ?: return@mapNotNull null
            val text = message.text.trim()
            if (text.length < MIN_TEXT_LENGTH ||
                !SemanticSearchGate.shouldEmbed(enabled = true, text = text)
            ) {
                null
            } else {
                message
            }
        }
        if (targets.isEmpty()) return 0

        var stored = 0
        for (batch in targets.chunked(EMBED_BATCH_SIZE)) {
            // A single failed batch must not abort the run: the next run retries the same rows.
            val vectors = runCatching { localEmbedder.embed(batch.map { it.text.trim() }) }
                .getOrNull()?.getOrNull() ?: continue
            batch.forEachIndexed { index, message ->
                val vector = vectors.getOrNull(index) ?: return@forEachIndexed
                val normalized = VectorCodec.normalize(vector)
                if (normalized.isEmpty()) return@forEachIndexed
                runCatching {
                    repository.putVector(message.id, normalized, LocalEmbedder.MODEL_ID)
                }
                    .onSuccess { stored++ }
                    .onFailure { Log.w(TAG, "Не удалось записать эмбеддинг сообщения", it) }
            }
        }
        return stored
    }

    companion object {
        private const val TAG = "Andharness"

        /** Bodies shorter than this carry too little meaning to be worth an inference. */
        const val MIN_TEXT_LENGTH = 8

        /** Small batch (8): bounds one on-device model run so the worker stays light on CPU. */
        const val EMBED_BATCH_SIZE = 8
    }
}
