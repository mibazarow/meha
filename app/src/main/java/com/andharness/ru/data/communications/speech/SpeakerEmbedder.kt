package com.andharness.ru.data.communications.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.andharness.ru.data.communications.audio.PcmAudio
import com.andharness.ru.data.embedding.EmbeddingPooling
import com.andharness.ru.domain.communications.speech.SpeechModel
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device speaker embeddings: runs the downloaded WeSpeaker ONNX model
 * ([SpeechModel.SPEAKER_EMB]) with ONNX Runtime Mobile on a 16 kHz mono PCM slice and returns a
 * fixed-dim, L2-normalised voice vector suitable for cosine-similarity clustering (see
 * [com.andharness.ru.domain.communications.Speaker]). Fully offline once the model is downloaded.
 *
 * Graph contract, inspected directly on `onnx/model_int8.onnx` (onnx 1.23, opset 14) and confirmed
 * by running it with onnxruntime:
 * - input `input_features`, float32, `[batch, frames, 80]` (80-dim Kaldi-style log-mel fbank);
 * - output `last_hidden_state`, float32, `[batch, 256]` ([EMBEDDING_DIM]).
 *
 * The fbank is computed by [Fbank] (with per-utterance CMN, as the WeSpeaker runtime does by
 * default); the embedding is L2-normalised with [EmbeddingPooling.l2Normalize] before returning.
 *
 * Threading/lifetime: the ORT session is created lazily, once per model path, and cached. [embed]
 * runs on [Dispatchers.IO] and never throws for ordinary failures — a missing model file, a
 * too-short clip, ORT errors or OOM all come back as a [Result.failure] with an honest Russian
 * message, so a diarisation pass can skip a bad slice instead of crashing.
 *
 * References:
 * - ONNX Runtime Java API (`OrtEnvironment.createSession(String, SessionOptions)`,
 *   `OnnxTensor.createTensor(.., FloatBuffer, long[])`, `OrtSession.run`) —
 *   https://onnxruntime.ai/docs/api/java/
 * - Model card (preprocessor_config: 80 bins, 25/10 ms, 16 kHz, hamming, snip_edges) —
 *   https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM
 */
@Singleton
class SpeakerEmbedder @Inject constructor(
    private val modelStore: SpeechModelStore,
) {

    @Volatile
    private var session: OrtSession? = null

    /** Absolute path of the file the cached [session] was created from. */
    @Volatile
    private var sessionPath: String? = null

    /** Stable id of the model that produces the embeddings, for `speakers.voice_model`. */
    fun modelId(model: SpeechModel = SpeechModel.SPEAKER_EMB): String = model.id

    /**
     * Computes an L2-normalised speaker embedding from [samples] (signed 16-bit PCM, 16 kHz, mono)
     * using [model]. Empty input, a missing/undownloaded model, a clip too short for the fbank, or
     * any ORT failure return `Result.failure` with a Russian message; never throws.
     */
    suspend fun embed(
        samples: ShortArray,
        model: SpeechModel = SpeechModel.SPEAKER_EMB,
    ): Result<FloatArray> = withContext(Dispatchers.IO) {
        try {
            if (samples.isEmpty()) {
                return@withContext fail("Пустой аудиофрагмент: нечего эмбеддить.")
            }
            val file = modelStore.targetFile(model)
            if (!file.isFile || !modelStore.isDownloaded(model)) {
                return@withContext fail("Голосовая модель не скачана: ${model.displayName}.")
            }

            val features = Fbank.compute(
                samples = samples,
                sampleRate = PcmAudio.TARGET_SAMPLE_RATE,
                numBins = NUM_MEL_BINS,
                applyCmn = true,
            )
            if (features.size < MIN_FRAMES) {
                return@withContext fail(
                    "Аудиофрагмент слишком короткий для голосового эмбеддинга " +
                        "(${features.size} кадров, нужно минимум $MIN_FRAMES)."
                )
            }

            val embedding = runSession(ensureSession(file), features)
            if (embedding.isEmpty()) {
                return@withContext fail("Модель вернула пустой голосовой эмбеддинг.")
            }
            Result.success(EmbeddingPooling.l2Normalize(embedding))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            fail("Недостаточно памяти для голосовой модели.", e)
        } catch (e: Exception) {
            fail("Голосовой эмбеддинг недоступен: ${e.message ?: e::class.java.simpleName}", e)
        }
    }

    /** Releases the cached native session. The process-wide [OrtEnvironment] is left open. */
    @Synchronized
    fun close() {
        session?.close()
        session = null
        sessionPath = null
    }

    /**
     * Returns the cached session for [file], creating it on first use (or when the path changed,
     * e.g. after a re-download). Synchronised so two concurrent embeddings cannot build two sessions.
     */
    @Synchronized
    private fun ensureSession(file: File): OrtSession {
        val path = file.absolutePath
        session?.let { if (sessionPath == path) return it }
        session?.close()

        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        val created = try {
            options.setIntraOpNumThreads(INTRA_OP_THREADS)
            options.setInterOpNumThreads(INTER_OP_THREADS)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(path, options)
        } finally {
            options.close()
        }

        session = created
        sessionPath = path
        return created
    }

    /** Runs the model over one `[1, frames, bins]` fbank and returns the raw `[dim]` output. */
    private fun runSession(session: OrtSession, features: Array<FloatArray>): FloatArray {
        val frames = features.size
        val bins = features[0].size
        val flat = FloatArray(frames * bins)
        var index = 0
        for (frame in features) {
            for (value in frame) flat[index++] = value
        }

        val shape = longArrayOf(1L, frames.toLong(), bins.toLong())
        val environment = OrtEnvironment.getEnvironment()
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), shape)
        try {
            session.run(mapOf(INPUT_FEATURES to input)).use { result ->
                val output = result.get(OUTPUT_EMBEDDING).orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("Модель не вернула $OUTPUT_EMBEDDING.")
                val buffer = output.floatBuffer
                val embedding = FloatArray(buffer.remaining())
                buffer.get(embedding)
                return embedding
            }
        } finally {
            input.close()
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Result<FloatArray> {
        Log.w(TAG, message, cause)
        return Result.failure(IllegalStateException(message, cause))
    }

    companion object {
        private const val TAG = "Andharness"

        /** Output dimension of `wespeaker-voxceleb-resnet34-LM` (`last_hidden_state` `[B, 256]`). */
        const val EMBEDDING_DIM = 256

        /** Mel bins the model expects (`num_mel_bins`). */
        const val NUM_MEL_BINS = 80

        /** Minimum number of fbank frames the model's `preprocessor_config` accepts. */
        private const val MIN_FRAMES = 9

        private const val INTRA_OP_THREADS = 2
        private const val INTER_OP_THREADS = 1

        // Graph I/O names, inspected on onnx/model_int8.onnx (opset 14).
        private const val INPUT_FEATURES = "input_features"
        private const val OUTPUT_EMBEDDING = "last_hidden_state"

        /** The model [SpeechModel.id] this embedder is built around. */
        const val MODEL_ID = "speaker_emb_wespeaker_resnet34"
    }
}
