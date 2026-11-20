package com.andharness.ru.data.chat

import android.util.Log
import com.andharness.ru.data.db.dao.ChatMessageVectorDao
import com.andharness.ru.data.db.entity.ChatMessageVectorEntity
import com.andharness.ru.data.embedding.ChatTextEmbedder
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.di.ApplicationScope
import com.andharness.ru.domain.retrieval.SemanticSearchGate
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.settings.LlmSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gives the app's OWN chat messages (`messages`, role `user` / `assistant`) an on-device semantic
 * index in `chat_message_vectors`, mirroring [com.andharness.ru.data.messages.StoredMessageVectorRepository]
 * for captured SMS/notifications. It is write-only: the search/query service and the UI are later
 * tasks.
 *
 * Three entry points, all bounded and best-effort:
 * - [indexAsync] — called when a message becomes final (right after the write); it never blocks the
 *   caller and never throws into it, because the embedding is launched on the process-lifetime
 *   [ApplicationScope].
 * - [embedOne] — the suspend body behind [indexAsync].
 * - [embedMissing] / [backfill] — the one-time bounded backfill for messages stored before this
 *   table existed.
 *
 * No work happens while `semanticSearchEnabled` is off, `tool` rows and short/blank bodies are
 * skipped, batches are small (so one run is cheap on CPU) and a failed batch is logged but never
 * aborts the run — the next run retries the same rows. Nothing here touches the network; the whole
 * path reuses the bundled offline [LocalEmbedder] (`rubert-tiny-turbo-int8`, dim 312).
 *
 * References:
 * - Entity/DAO: https://developer.android.com/training/data-storage/room/defining-data ,
 *   https://developer.android.com/training/data-storage/room/accessing-data
 * - Bounded coroutine wait: `withTimeoutOrNull` —
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html
 * - Structured concurrency / launching on a process scope — https://kotlinlang.org/docs/coroutines-basics.html
 */
@Singleton
class ChatMessageVectorIndexer @Inject constructor(
    private val vectorDao: ChatMessageVectorDao,
    private val embedder: ChatTextEmbedder,
    private val settingsStore: LlmSettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Fire-and-forget indexing of [messageId] on the process scope. Safe to call from a write path:
     * it returns immediately and any failure is logged, never propagated to the caller. No-op for a
     * non-positive id.
     */
    fun indexAsync(messageId: Long) {
        if (messageId <= 0L) return
        scope.launch {
            runCatching { embedOne(messageId) }
                .onFailure { Log.w(TAG, "Не удалось проиндексировать сообщение чата", it) }
        }
    }

    /**
     * Embeds ONE message and upserts its vector. Returns true when a fresh vector was stored.
     * No-op (false) when semantic search is off, the message is missing / not `user`/`assistant`,
     * its body is too short, or the model call fails. Never throws.
     */
    suspend fun embedOne(messageId: Long): Boolean {
        if (messageId <= 0L) return false
        if (!semanticSearchEnabled()) return false
        val message = runCatching { vectorDao.messageForEmbedding(messageId) }.getOrNull()
            ?: return false
        if (!ChatMessageEmbeddingPlan.shouldIndex(message.role, message.content, enabled = true)) {
            return false
        }
        val vector = runCatching {
            withTimeoutOrNull(EMBED_ONE_TIMEOUT_MS) { embedder.embed(listOf(message.content.trim())) }
        }.getOrNull()?.getOrNull()?.firstOrNull() ?: return false
        val normalized = VectorCodec.normalize(vector)
        if (normalized.isEmpty()) return false
        return runCatching { vectorDao.upsert(record(messageId, normalized)) }
            .onFailure { Log.w(TAG, "Не удалось записать эмбеддинг сообщения чата", it) }
            .isSuccess
    }

    /**
     * Embeds up to [limit] `user`/`assistant` messages that still have no vector, in one bounded pass
     * (small in-batch chunks). Returns how many vectors were actually written (0 when semantic search
     * is off, nothing is missing, or every batch failed).
     */
    suspend fun embedMissing(limit: Int): Int {
        if (limit <= 0) return 0
        if (!semanticSearchEnabled()) return 0

        val candidates = runCatching {
            vectorDao.missingMessagesForEmbedding(limit, ChatMessageEmbeddingPlan.MIN_TEXT_LENGTH)
        }.getOrDefault(emptyList())
        val targets = candidates.filter {
            ChatMessageEmbeddingPlan.shouldIndex(it.role, it.content, enabled = true)
        }
        if (targets.isEmpty()) return 0

        var stored = 0
        for (batch in targets.chunked(EMBED_BATCH_SIZE)) {
            // A single failed batch must not abort the run: the next run retries the same rows.
            val vectors = runCatching { embedder.embed(batch.map { it.content.trim() }) }
                .getOrNull()?.getOrNull() ?: continue
            val rows = ArrayList<ChatMessageVectorEntity>(batch.size)
            batch.forEachIndexed { index, message ->
                val vector = vectors.getOrNull(index) ?: return@forEachIndexed
                if (vector.isEmpty()) return@forEachIndexed
                val normalized = VectorCodec.normalize(vector)
                if (normalized.isEmpty()) return@forEachIndexed
                rows += record(message.id, normalized)
            }
            if (rows.isNotEmpty()) {
                runCatching { vectorDao.upsertAll(rows) }
                    .onFailure { Log.w(TAG, "Не удалось записать эмбеддинги сообщений чата", it) }
                    .onSuccess { stored += rows.size }
            }
        }
        return stored
    }

    /**
     * Bounded, idempotent backfill for chat messages stored before the index existed. Runs at most
     * [maxBatches] passes of [BACKFILL_BATCH_SIZE] messages, stopping early once nothing is left to
     * embed. Returns the total number of vectors written. Never throws.
     */
    suspend fun backfill(maxBatches: Int): Int {
        if (maxBatches <= 0) return 0
        var total = 0
        repeat(maxBatches) {
            val stored = embedMissing(BACKFILL_BATCH_SIZE)
            if (stored <= 0) return total
            total += stored
        }
        return total
    }

    private fun record(messageId: Long, vector: FloatArray): ChatMessageVectorEntity =
        ChatMessageVectorEntity(
            messageId = messageId,
            dim = vector.size,
            vector = VectorCodec.encode(vector),
            model = LocalEmbedder.MODEL_ID,
            updatedAt = System.currentTimeMillis()
        )

    /** Reads the `semanticSearchEnabled` switch; a failed read is treated as OFF. */
    private suspend fun semanticSearchEnabled(): Boolean = runCatching {
        settingsStore.config.firstOrNull()?.semanticSearchEnabled == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "Andharness"

        /** Small batch (8): bounds one on-device model run so indexing stays light on CPU. */
        const val EMBED_BATCH_SIZE = 8

        /** Messages fetched per backfill pass; each pass is drained in [EMBED_BATCH_SIZE] model runs. */
        const val BACKFILL_BATCH_SIZE = 64

        /**
         * Bounds one save-time inference. The model is warmed at startup, so this is normally tens of
         * ms; the cap only protects against a stuck/failed run.
         */
        const val EMBED_ONE_TIMEOUT_MS = 3_000L
    }
}

/**
 * Pure, JVM-testable decisions of the chat-message index: which rows are worth embedding and how a
 * candidate set is batched. Kept separate from [ChatMessageVectorIndexer] so the skip/batch rules
 * can be covered without Room or the ONNX model.
 */
object ChatMessageEmbeddingPlan {

    /** Bodies shorter than this carry too little meaning to be worth an inference. */
    const val MIN_TEXT_LENGTH = 8

    /** Only the visible transcript roles are indexed; `tool` rows are never rendered or searched. */
    val INDEXED_ROLES: Set<String> = setOf("user", "assistant")

    /**
     * `true` only when semantic search is on, the role is `user`/`assistant`, and the trimmed body is
     * long enough and non-blank to be worth embedding.
     */
    fun shouldIndex(role: String, content: String, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (role !in INDEXED_ROLES) return false
        val text = content.trim()
        if (text.length < MIN_TEXT_LENGTH) return false
        return SemanticSearchGate.shouldEmbed(enabled = true, text = text)
    }
}
