package com.andharness.ru.domain.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin codec for dense embedding vectors. No Android/Room dependency, so it
 * is unit-testable on the JVM.
 *
 * Storage format: [encode] writes the floats as little-endian IEEE-754 `float32` in a `BLOB`, four
 * bytes per element — the compact, provider-agnostic form the `memory_vectors` table stores. The
 * byte order is fixed (little-endian) so a database written on one device can be read on another.
 *
 * [normalize] returns a unit-length copy, which is what makes the stored vectors directly comparable
 * with a plain dot product; [TextRetriever.cosine] is defensive and normalises again anyway.
 */
object VectorCodec {

    /** Bytes per stored element (`float32`). */
    const val BYTES_PER_FLOAT = Float.SIZE_BYTES

    /** Serialises [vector] to little-endian float32 bytes. An empty vector becomes an empty array. */
    fun encode(vector: FloatArray): ByteArray {
        if (vector.isEmpty()) return ByteArray(0)
        val buffer = ByteBuffer.allocate(vector.size * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) buffer.putFloat(value)
        return buffer.array()
    }

    /**
     * Parses little-endian float32 [bytes] back into a vector. A byte length that is not a multiple
     * of [BYTES_PER_FLOAT] is treated as corrupt and yields an empty vector (never throws), so one
     * bad row can never break recall.
     */
    fun decode(bytes: ByteArray): FloatArray {
        if (bytes.isEmpty() || bytes.size % BYTES_PER_FLOAT != 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / BYTES_PER_FLOAT)
        for (i in out.indices) out[i] = buffer.getFloat()
        return out
    }

    /**
     * Returns a unit-length copy of [vector]. A zero/degenerate vector (or a NaN/infinite one) is
     * returned unchanged, because normalising it is meaningless — callers must treat it as absent.
     */
    fun normalize(vector: FloatArray): FloatArray = VectorMath.l2Normalize(vector)
}
