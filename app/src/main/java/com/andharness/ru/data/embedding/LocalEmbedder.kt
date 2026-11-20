package com.andharness.ru.data.embedding

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext

/**
 * On-device embeddings, fully offline: ONNX Runtime Mobile runs the bundled int8
 * `rubert-tiny-turbo` model over `assets/models/embedding/`. No provider endpoint, no API key, no
 * network — the whole point is that semantic memory search keeps working on a phone with no LLM
 * backend configured.
 *
 * Model facts (verified against the bundled artifacts):
 * - engine: ONNX Runtime Mobile 1.23.1 (`ai.onnxruntime`), MIT;
 * - model: `sergeyzh/rubert-tiny-turbo` int8 ONNX (a drop-in replacement for the previous
 *   `TrendHD/rubert-tiny2-int8`); sha256 and size are pinned in [MODEL_SHA256] / [MODEL_SIZE_BYTES];
 * - graph: unchanged I/O — inputs `input_ids` / `attention_mask` / `token_type_ids` (int64,
 *   `[batch, seq]`), output `last_hidden_state` (`[batch, seq, 312]`); [dim] = 312;
 * - pooling: `[CLS]` (token 0) then L2 normalise ([EmbeddingPooling]);
 * - tokenizer: cased WordPiece with `vocab.txt` ([WordPieceTokenizer]); the bundled vocab is
 *   token-identical to turbo's, so it is kept as-is.
 *
 * Threading/lifetime: the session and vocab are loaded once, lazily and thread-safely, and reused.
 * Inference runs on [Dispatchers.Default] in bounded batches so it never blocks the main thread and
 * cannot allocate an unbounded tensor. [embed] never throws for ordinary failures (missing asset,
 * ORT error, out of memory) — it returns a [Result.failure] with an honest Russian message, so the
 * recall path can degrade to lexical-only instead of crashing a chat turn.
 *
 * References:
 * - ONNX Runtime Mobile / custom builds — https://onnxruntime.ai/docs/build/custom.html
 * - ORT Java API (`OrtEnvironment.createSession(byte[])`, `OrtSession.run`, `OnnxTensor`) —
 *   https://onnxruntime.ai/docs/api/java/
 * - Android assets (`AssetManager.open`) —
 *   https://developer.android.com/guide/topics/resources/providing-resources#Assets
 */
class LocalEmbedder(
    private val assetManager: AssetManager,
    private val modelAssetPath: String = DEFAULT_MODEL_ASSET,
    private val vocabAssetPath: String = DEFAULT_VOCAB_ASSET,
    private val maxSeqLen: Int = DEFAULT_MAX_SEQ_LEN,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : ChatTextEmbedder {

    /** Embedding dimension produced by the bundled model (hidden size of `rubert-tiny-turbo`). */
    val dim: Int = EMBEDDING_DIM

    private data class Loaded(
        val environment: OrtEnvironment,
        val session: OrtSession,
        val tokenizer: WordPieceTokenizer,
    )

    @Volatile
    private var loaded: Loaded? = null

    /**
     * Encodes [texts] (order-preserving) to L2-normalised vectors of length [dim]. Empty input is an
     * empty success. Bounded: at most [batchSize] texts per ORT run, each truncated to [maxSeqLen].
     */
    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.success(emptyList())
        return withContext(Dispatchers.Default) {
            try {
                val deps = ensureLoaded()
                val vectors = ArrayList<FloatArray>(texts.size)
                for (chunk in texts.chunked(batchSize)) {
                    coroutineContext.ensureActive()
                    vectors += runBatch(deps, chunk)
                }
                Result.success(vectors)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                fail("Недостаточно памяти для локальной модели эмбеддингов.", e)
            } catch (e: Exception) {
                fail("Локальные эмбеддинги недоступны: ${e.message ?: e::class.java.simpleName}", e)
            }
        }
    }

    /**
     * Eagerly loads the bundled model session (reusing [ensureLoaded]) so the first real [embed]
     * call does not pay the ~29 MB model load on the hot path. Runs on [Dispatchers.Default] and
     * never throws: a missing asset or ORT failure is logged and swallowed, and an
     * [OutOfMemoryError] is caught the same way, because warming up is an optimisation and must not
     * break startup. The caller uses it at startup and must not call it while semantic search is off.
     */
    suspend fun warmUp() {
        withContext(Dispatchers.Default) {
            try {
                ensureLoaded()
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Недостаточно памяти для прогрева локальной модели эмбеддингов.", e)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Прогрев локальной модели эмбеддингов не удался: " +
                        "${e.message ?: e::class.java.simpleName}",
                    e
                )
            }
        }
    }

    /** Releases the native session. The process-wide [OrtEnvironment] is intentionally left open. */
    @Synchronized
    fun close() {
        loaded?.session?.close()
        loaded = null
    }

    @Synchronized
    private fun ensureLoaded(): Loaded {
        loaded?.let { return it }

        // Read the vocab first: it is small, and a missing asset should fail with a clear message
        // before we allocate ~29 MB for the model.
        val vocabText = assetManager.open(vocabAssetPath).use { it.readBytes().decodeToString() }
        val tokenizer = WordPieceTokenizer.fromVocabText(vocabText)

        // The model is ~29 MB; read it to a byte array (ORT copies it into native memory), then let
        // the array become garbage so the session is the only long-lived copy.
        val modelBytes = assetManager.open(modelAssetPath).use { it.readBytes() }

        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        val session = try {
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            options.setInterOpNumThreads(INTER_OP_THREADS)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(modelBytes, options)
        } finally {
            options.close()
        }

        return Loaded(environment, session, tokenizer).also { loaded = it }
    }

    /** Runs one bounded batch and returns CLS-pooled, L2-normalised vectors in input order. */
    private fun runBatch(deps: Loaded, batch: List<String>): List<FloatArray> {
        val tokens = deps.tokenizer.encodeBatch(batch, maxSeqLen)
        val shape = longArrayOf(batch.size.toLong(), tokens.seqLen.toLong())

        val inputIds = OnnxTensor.createTensor(deps.environment, LongBuffer.wrap(tokens.inputIds), shape)
        val attentionMask = OnnxTensor.createTensor(deps.environment, LongBuffer.wrap(tokens.attentionMask), shape)
        val tokenTypeIds = OnnxTensor.createTensor(deps.environment, LongBuffer.wrap(tokens.tokenTypeIds), shape)
        try {
            val inputs = mapOf(
                INPUT_IDS to inputIds,
                ATTENTION_MASK to attentionMask,
                TOKEN_TYPE_IDS to tokenTypeIds,
            )
            deps.session.run(inputs).use { result ->
                val output = result.get(OUTPUT_LAST_HIDDEN_STATE).orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("Модель не вернула $OUTPUT_LAST_HIDDEN_STATE.")
                val buffer = output.floatBuffer
                val hidden = FloatArray(buffer.remaining())
                buffer.get(hidden)

                return batch.indices.map { index ->
                    EmbeddingPooling.l2Normalize(
                        EmbeddingPooling.clsToken(hidden, index, tokens.seqLen, dim)
                    )
                }
            }
        } finally {
            inputIds.close()
            attentionMask.close()
            tokenTypeIds.close()
        }
    }

    private fun fail(message: String, cause: Throwable): Result<List<FloatArray>> {
        Log.w(TAG, message, cause)
        return Result.failure(IllegalStateException(message, cause))
    }

    companion object {
        private const val TAG = "Andharness"

        /** Hidden size of `rubert-tiny-turbo` (config `hidden_size` / `emb_size`). */
        const val EMBEDDING_DIM = 312

        /**
         * Идентификатор модели для тега `memory_vectors.model`: сменился — сохранённый вектор
         * считается устаревшим и фоновая переиндексация пересчитает его. Держать в согласии с
         * именем бандла `assets/models/embedding/rubert-tiny-turbo-int8.onnx`.
         */
        const val MODEL_ID = "rubert-tiny-turbo-int8"

        /** Bundled int8 ONNX asset; the exact filename the asset open in [ensureLoaded] uses. */
        const val DEFAULT_MODEL_ASSET = "models/embedding/rubert-tiny-turbo-int8.onnx"

        /**
         * Bundled cased WordPiece vocab (83828 entries). Token-identical to `rubert-tiny-turbo`'s,
         * so it was kept unchanged when the ONNX model was swapped.
         */
        const val DEFAULT_VOCAB_ASSET = "models/embedding/vocab.txt"

        /**
         * Integrity facts of the bundled [DEFAULT_MODEL_ASSET], pinned so a corrupted/wrong asset is
         * detectable: sha256 of `assets/models/embedding/rubert-tiny-turbo-int8.onnx` and its exact
         * byte size (29,240,209). The file is a drop-in for the previous `rubert-tiny2-int8` model.
         */
        const val MODEL_SHA256 = "05341664814dc06efaf7578e78d3b8de718af60fd45d8ee40e3b47f9016c20cb"
        const val MODEL_SIZE_BYTES = 29240209L

        /**
         * Practical sequence cap. The model accepts up to 2048 positions, but on-device attention is
         * O(seq²) and memory entries are short; 256 keeps one inference cheap.
         */
        const val DEFAULT_MAX_SEQ_LEN = 256

        /** Texts per ORT run: bounds peak tensor memory for a worker batch. */
        const val DEFAULT_BATCH_SIZE = 8

        // Graph input/output names, parsed from the bundled ONNX model.
        private const val INPUT_IDS = "input_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val TOKEN_TYPE_IDS = "token_type_ids"
        private const val OUTPUT_LAST_HIDDEN_STATE = "last_hidden_state"

        private const val INTRA_OP_THREADS = 2
        private const val INTER_OP_THREADS = 1

        @Volatile
        private var instance: LocalEmbedder? = null

        /**
         * Process-wide shared embedder. Lazy and thread-safe; the model loads on first [embed].
         * Prefer this over constructing [LocalEmbedder] directly so the ~29 MB session is created
         * once per process.
         */
        fun get(context: Context): LocalEmbedder =
            instance ?: synchronized(this) {
                instance ?: LocalEmbedder(context.applicationContext.assets).also { instance = it }
            }
    }
}
