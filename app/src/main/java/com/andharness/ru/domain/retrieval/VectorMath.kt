package com.andharness.ru.domain.retrieval

import kotlin.math.sqrt

/**
 * Shared pure-Kotlin float-vector math. No Android/Room dependency, so it is unit-testable on the
 * JVM — the single home of the L2-normalisation and cosine-similarity primitive used by retrieval,
 * embeddings and diarisation.
 *
 * The former per-package copies of [l2Normalize] were byte-identical, so this implementation is
 * exactly that behaviour and every delegating caller keeps identical numerical results.
 * [cosineSimilarity] keeps the strictest existing semantics (empty/size/finite guards, zero-magnitude
 * guard, clamped to `[-1, 1]`).
 *
 * References:
 * - Cosine similarity (definition, range `[-1, +1]`, magnitude invariance) —
 *   https://en.wikipedia.org/wiki/Cosine_similarity
 * - `kotlin.math.sqrt` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/sqrt.html
 * - `Float.isFinite()` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/is-finite.html
 * - `coerceIn` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.ranges/coerce-in.html
 */
object VectorMath {

    /**
     * Returns a unit-length copy of [vector]. A zero/degenerate or non-finite vector is returned
     * unchanged (the SAME instance), because normalising it is meaningless — callers must treat it
     * as absent.
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        if (vector.isEmpty()) return vector
        var sumSquares = 0.0
        for (value in vector) {
            if (!value.isFinite()) return vector
            sumSquares += value.toDouble() * value.toDouble()
        }
        val norm = sqrt(sumSquares)
        if (norm <= 0.0) return vector
        val out = FloatArray(vector.size)
        for (i in vector.indices) out[i] = (vector[i] / norm).toFloat()
        return out
    }

    /**
     * Cosine similarity of [a] and [b] in `[-1, 1]`; `0` when either vector is empty, the sizes
     * differ, a component is non-finite, or either vector has zero magnitude (all of which mean
     * "no usable similarity"). Assumes same-length vectors but re-normalises for any input.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            if (!x.isFinite() || !y.isFinite()) return 0f
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }
}
