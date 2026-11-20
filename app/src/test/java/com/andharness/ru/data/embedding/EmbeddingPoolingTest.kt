package com.andharness.ru.data.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-JVM coverage of the CLS pooling + L2 normalisation used by [LocalEmbedder] for the bundled
 * `rubert-tiny-turbo-int8` model (its Sentence-Transformers config is `cls_token=true` then `Normalize`).
 */
class EmbeddingPoolingTest {

    @Test
    fun clsTokenExtractsTheFirstVectorOfTheFirstItem() {
        // Shape [1, 2, 3]: token 0 is the [CLS] vector.
        val hidden = floatArrayOf(0.1f, 0.2f, 0.3f, 9f, 9f, 9f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), EmbeddingPooling.clsToken(hidden, 0, 2, 3), 0.0f)
    }

    @Test
    fun clsTokenUsesTheRightOffsetForLaterBatchItems() {
        // Shape [2, 2, 3]: item 1's [CLS] vector starts after 1 * 2 * 3 elements.
        val hidden = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f)
        assertArrayEquals(floatArrayOf(7f, 8f, 9f), EmbeddingPooling.clsToken(hidden, 1, 2, 3), 0.0f)
    }

    @Test
    fun clsTokenReturnsEmptyForOutOfRangeAccess() {
        val hidden = floatArrayOf(1f, 2f, 3f)
        assertEquals(0, EmbeddingPooling.clsToken(hidden, 3, 1, 3).size)
        assertEquals(0, EmbeddingPooling.clsToken(hidden, -1, 1, 3).size)
        assertEquals(0, EmbeddingPooling.clsToken(hidden, 0, 0, 3).size)
    }

    @Test
    fun l2NormalizeProducesUnitLengthAndKeepsDirection() {
        val unit = EmbeddingPooling.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, unit[0], 1e-6f)
        assertEquals(0.8f, unit[1], 1e-6f)
        assertEquals(1.0, sqrt((unit[0] * unit[0] + unit[1] * unit[1]).toDouble()), 1e-6)
    }

    @Test
    fun normalizedVectorsOfParallelInputsAreIdentical() {
        val a = EmbeddingPooling.l2Normalize(floatArrayOf(1f, 2f, -3f))
        val b = EmbeddingPooling.l2Normalize(floatArrayOf(2f, 4f, -6f))
        assertArrayEquals(a, b, 1e-6f)
    }

    @Test
    fun l2NormalizeLeavesDegenerateAndNonFiniteVectorsUnchanged() {
        assertArrayEquals(floatArrayOf(0f, 0f), EmbeddingPooling.l2Normalize(floatArrayOf(0f, 0f)), 0.0f)
        assertEquals(0, EmbeddingPooling.l2Normalize(FloatArray(0)).size)
        val nan = floatArrayOf(Float.NaN, 1f)
        assertArrayEquals(nan, EmbeddingPooling.l2Normalize(nan), 0.0f)
        val inf = floatArrayOf(Float.POSITIVE_INFINITY, 1f)
        assertArrayEquals(inf, EmbeddingPooling.l2Normalize(inf), 0.0f)
    }

    @Test
    fun poolingThenNormalisingMatchesAManuallyComputedUnitVector() {
        val hidden = floatArrayOf(3f, 0f, 0f, 9f, 9f, 9f)
        val pooled = EmbeddingPooling.clsToken(hidden, 0, 2, 3)
        val normalized = EmbeddingPooling.l2Normalize(pooled)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), normalized, 1e-6f)
        assertTrue(normalized.all { it.isFinite() })
    }
}
