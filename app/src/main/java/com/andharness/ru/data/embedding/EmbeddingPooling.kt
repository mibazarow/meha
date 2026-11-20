package com.andharness.ru.data.embedding

import com.andharness.ru.domain.retrieval.VectorMath

/**
 * Pure-Kotlin pooling/normalisation for the bundled `rubert-tiny-turbo-int8` model. No Android
 * dependency, so it is unit-testable on the JVM.
 *
 * Pooling rule: the bundled `sergeyzh/rubert-tiny-turbo` int8 export keeps the Sentence-Transformers
 * pooling of its predecessor (this ONNX swap is a drop-in): `[CLS]` token (0) pooling followed by an
 * L2 `Normalize`. So the sentence vector is the **`[CLS]` hidden state (token 0)**, then
 * L2-normalised. This is NOT mean pooling — using mean pooling here would silently degrade recall.
 *
 * References:
 * - Model card: https://huggingface.co/sergeyzh/rubert-tiny-turbo
 * - Sentence-Transformers pooling modules: https://sbert.net/docs/package_reference/sentence_transformer/models.html
 */
object EmbeddingPooling {

    /**
     * Extracts the `[CLS]` vector for item [batchIndex] from a flat `last_hidden_state` buffer of
     * shape `[batch, seqLen, dim]`. Returns an empty vector when the buffer is too small, so one
     * malformed inference result cannot throw into the caller.
     */
    fun clsToken(hiddenStates: FloatArray, batchIndex: Int, seqLen: Int, dim: Int): FloatArray {
        if (batchIndex < 0 || seqLen <= 0 || dim <= 0) return FloatArray(0)
        val start = batchIndex * seqLen * dim
        val end = start + dim
        if (start < 0 || end > hiddenStates.size) return FloatArray(0)
        return hiddenStates.copyOfRange(start, end)
    }

    /**
     * Returns a unit-length copy of [vector]. A zero/degenerate or non-finite vector is returned
     * unchanged (normalising it is meaningless) and callers must treat it as absent.
     */
    fun l2Normalize(vector: FloatArray): FloatArray = VectorMath.l2Normalize(vector)
}
