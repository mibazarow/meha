package com.andharness.ru.domain.communications.diarization

import com.andharness.ru.domain.retrieval.VectorMath

/**
 * Pure float-vector math used by the diarisation logic ([VoiceClusterer]). No Android/Room
 * dependency, so it is unit-testable on the JVM.
 *
 * Voice embeddings are fixed-dim, L2-normalised vectors (see
 * [com.andharness.ru.data.communications.speech.SpeakerEmbedder]); cosine similarity is the natural
 * comparison metric for them because it ignores vector magnitude and only measures the angle
 * between two voiceprints. [cosine] re-normalises defensively, so it is correct for raw or
 * already-normalised inputs alike.
 *
 * [l2Normalize] and [cosine] delegate to [com.andharness.ru.domain.retrieval.VectorMath] so the
 * diarisation package shares one implementation with the rest of the app; the semantics are
 * unchanged (degenerate/non-finite vectors are returned unchanged).
 *
 * References:
 * - Cosine similarity (definition, range `[-1, +1]`, magnitude invariance) —
 *   https://en.wikipedia.org/wiki/Cosine_similarity
 * - `sklearn.metrics.pairwise.cosine_similarity` —
 *   https://scikit-learn.org/stable/modules/generated/sklearn.metrics.pairwise.cosine_similarity.html
 */
object VoiceVectorMath {

    /**
     * Cosine similarity of [a] and [b] in `[-1, 1]`; `0` when either vector is empty, the sizes
     * differ, a component is non-finite, or either vector has zero magnitude (all of which mean
     * "no usable similarity"). Assumes same-length vectors but re-normalises for any input.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float = VectorMath.cosineSimilarity(a, b)

    /**
     * Component-wise mean of [vectors] (the centroid of a cluster). The dimension is taken from the
     * first non-empty vector; vectors of a different length are skipped so one malformed sample
     * cannot corrupt the mean. Returns an empty vector for empty input or when nothing matches.
     */
    fun mean(vectors: List<FloatArray>): FloatArray {
        val dim = vectors.firstOrNull { it.isNotEmpty() }?.size ?: return FloatArray(0)
        val out = FloatArray(dim)
        var count = 0
        for (vector in vectors) {
            if (vector.size != dim) continue
            for (i in 0 until dim) out[i] += vector[i]
            count++
        }
        if (count == 0) return FloatArray(0)
        for (i in out.indices) out[i] /= count
        return out
    }

    /**
     * Weighted component-wise mean of [a] (weight [weightA]) and [b] (weight [weightB]), used to fold
     * a new cluster centroid into an existing speaker centroid by sample count. Negative weights are
     * treated as `0`; when both weights are `0` (or equal length is unavailable) the two vectors are
     * averaged with equal weight, and mismatched/empty dimensions yield an empty vector so the caller
     * can fall back to the fresher centroid instead of merging incompatible vectors.
     */
    fun weightedMean(a: FloatArray, weightA: Int, b: FloatArray, weightB: Int): FloatArray {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return FloatArray(0)
        val wa = weightA.coerceAtLeast(0).toDouble()
        val wb = weightB.coerceAtLeast(0).toDouble()
        val total = wa + wb
        if (total <= 0.0) return mean(listOf(a, b))
        val out = FloatArray(a.size)
        for (i in a.indices) {
            val value = (a[i].toDouble() * wa + b[i].toDouble() * wb) / total
            if (!value.isFinite()) return FloatArray(0)
            out[i] = value.toFloat()
        }
        return out
    }

    /**
     * Returns a unit-length copy of [vector]. A zero/degenerate or non-finite vector is returned
     * unchanged, because normalising it is meaningless — callers must treat it as absent.
     */
    fun l2Normalize(vector: FloatArray): FloatArray = VectorMath.l2Normalize(vector)
}
