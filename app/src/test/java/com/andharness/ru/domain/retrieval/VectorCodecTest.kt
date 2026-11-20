package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the dense-vector codec used by `memory_vectors` — the
 * little-endian float32 round trip, corruption tolerance, and normalisation.
 */
class VectorCodecTest {

    @Test
    fun encodeDecodeRoundTripsLittleEndianFloat32() {
        val vector = floatArrayOf(0f, 1f, -2.5f, 3.75f, 1e-3f)
        val bytes = VectorCodec.encode(vector)
        assertEquals("4 bytes per float", vector.size * 4, bytes.size)
        assertArrayEquals(vector, VectorCodec.decode(bytes), 0.0f)
    }

    @Test
    fun encodingIsLittleEndian() {
        // 1.0f is 0x3F800000; little-endian stores the lowest byte first.
        val bytes = VectorCodec.encode(floatArrayOf(1f))
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x80.toByte(), bytes[2])
        assertEquals(0x3F.toByte(), bytes[3])
    }

    @Test
    fun emptyVectorRoundTripsToEmpty() {
        assertEquals(0, VectorCodec.encode(FloatArray(0)).size)
        assertEquals(0, VectorCodec.decode(ByteArray(0)).size)
    }

    @Test
    fun corruptByteLengthYieldsEmptyInsteadOfThrowing() {
        assertEquals(0, VectorCodec.decode(byteArrayOf(1, 2, 3)).size)
    }

    @Test
    fun normalizeProducesUnitLengthAndKeepsDirection() {
        val unit = VectorCodec.normalize(floatArrayOf(3f, 4f))
        assertEquals(1.0, kotlin.math.sqrt((unit[0] * unit[0] + unit[1] * unit[1]).toDouble()), 1e-6)
        assertEquals(0.6f, unit[0], 1e-6f)
        assertEquals(0.8f, unit[1], 1e-6f)
    }

    @Test
    fun normalizeLeavesDegenerateAndNonFiniteVectorsUnchanged() {
        assertArrayEquals(floatArrayOf(0f, 0f), VectorCodec.normalize(floatArrayOf(0f, 0f)), 0.0f)
        val nan = floatArrayOf(Float.NaN, 1f)
        assertArrayEquals(nan, VectorCodec.normalize(nan), 0.0f)
    }

    @Test
    fun normalizedVectorHasCosineOneWithItself() {
        val v = VectorCodec.normalize(floatArrayOf(1f, 2f, -3f))
        assertEquals(1.0, TextRetriever.cosine(v, v), 1e-6)
        assertTrue(TextRetriever.cosine(v, VectorCodec.normalize(floatArrayOf(-2f, -4f, 6f))) < -0.99)
    }
}
