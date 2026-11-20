package com.andharness.ru.data.assist

import android.util.Log
import com.andharness.ru.data.db.dao.MemoryVectorDao
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.embedding.ChatTextEmbedder
import com.andharness.ru.data.embedding.LocalEmbedder
import com.andharness.ru.domain.retrieval.TextRetriever
import com.andharness.ru.domain.retrieval.VectorCodec
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Semantic duplicate check at write time (BUGS.md M6).
 *
 * The text-based [com.andharness.ru.domain.retrieval.MemoryConsolidation] already merges a
 * restatement that shares half its tokens. What it cannot catch is the same fact said in different
 * words — «арендовал склад на Ленина» vs «снял помещение под хранение товаров на Ленина». The
 * memory↔person/entity graph does not help either: those are different entities.
 *
 * So before INSERTING a new memory, compare its embedding with the embeddings already stored for
 * recent memories and treat a cosine at or above [DUPLICATE_COSINE] as the same fact. The caller
 * then updates that row instead of adding a twin.
 *
 * Deliberately best-effort and bounded: any of «semantic search off», «no embedder», «no stored
 * vectors», «embedding failed/timeout» returns `null` and the caller inserts as before. This never
 * blocks a save for long and never throws.
 *
 * Note on what is NOT copied from Оборбот: it gathers facts under a read lock and then makes N HTTP
 * embedding calls outside it. Here the embedder is local and the comparison is a bounded loop over
 * already-decoded vectors — no lock is ever held across I/O.
 */
internal class MemorySemanticDedup(
    private val memoryVectorDao: MemoryVectorDao?,
    private val embedder: ChatTextEmbedder?,
    private val settingsStore: LlmSettingsStore?
) {

    /**
     * The id of an existing memory whose meaning is the same as [text], or `null` when there is no
     * confident duplicate (or the semantic layer is unavailable).
     */
    suspend fun duplicateOf(text: String, candidates: List<NoteEntity>): Long? {
        if (text.isBlank() || candidates.isEmpty()) return null
        val dao = memoryVectorDao ?: return null
        val model = embedder ?: return null
        val store = settingsStore ?: return null
        val enabled = runCatching { store.config.firstOrNull()?.semanticSearchEnabled == true }
            .getOrDefault(false)
        if (!enabled) return null

        val stored = runCatching { dao.byIds(candidates.map { it.id }) }
            .getOrDefault(emptyList())
            .filter { it.model == LocalEmbedder.MODEL_ID }
        if (stored.isEmpty()) return null

        val vector = withTimeoutOrNull(EMBED_TIMEOUT_MS) {
            model.embed(listOf(text)).getOrNull()?.firstOrNull()
        } ?: return null
        if (vector.isEmpty()) return null

        var bestId: Long? = null
        var best = 0.0
        for (row in stored) {
            val cosine = TextRetriever.cosine(vector, VectorCodec.decode(row.vector))
            if (!cosine.isFinite()) continue
            if (cosine > best) {
                best = cosine
                bestId = row.memoryId
            }
        }
        // Always report the best cosine: without a number the threshold is a guess, and tuning it
        // later would be blind (BUGS.md: measure first).
        Log.d(
            TAG,
            "Лучшее совпадение #$bestId cosine=${"%.2f".format(best)} " +
                "из ${stored.size} векторов, порог $DUPLICATE_COSINE"
        )
        if (best < DUPLICATE_COSINE) return null
        return bestId
    }

    private companion object {
        /**
         * Cosine at which two texts are the same fact. The bundled `rubert-tiny-turbo-int8` scores a
         * paraphrase of the same statement around 0.90–0.97 while two different facts about the same
         * topic land near 0.75–0.85, so 0.90 is the point where merging is safe. A TUNABLE.
         */
        const val DUPLICATE_COSINE = 0.90

        /** A save must never hang on the embedder; on timeout we just insert as before. */
        const val EMBED_TIMEOUT_MS = 4_000L

        const val TAG = "MemorySemanticDedup"
    }
}
