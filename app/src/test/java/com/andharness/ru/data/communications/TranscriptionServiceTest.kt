package com.andharness.ru.data.communications

import com.andharness.ru.data.communications.speech.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure helpers of [TranscriptionService]: [normalizeSegments] (trim/drop-blank/
 * clamp/order) and [asBatches] (bounded chunking used for embedding). No Android APIs are touched.
 */
class TranscriptionServiceTest {

    @Test
    fun normalizeTrimsText() {
        val out = normalizeSegments(listOf(WhisperSegment(0L, 1000L, "  привет  ")))

        assertEquals(listOf(WhisperSegment(0L, 1000L, "привет")), out)
    }

    @Test
    fun normalizeDropsBlankText() {
        val out = normalizeSegments(
            listOf(
                WhisperSegment(0L, 10L, ""),
                WhisperSegment(10L, 20L, "   "),
                WhisperSegment(20L, 30L, "ok"),
            )
        )

        assertEquals(listOf(WhisperSegment(20L, 30L, "ok")), out)
    }

    @Test
    fun normalizeClampsEndUpToStart() {
        val out = normalizeSegments(listOf(WhisperSegment(5000L, 2000L, "x")))

        assertEquals(5000L, out.single().startMs)
        assertEquals(5000L, out.single().endMs)
    }

    @Test
    fun normalizeClampsNegativeTimesToZero() {
        val out = normalizeSegments(listOf(WhisperSegment(-100L, -50L, "x")))

        assertEquals(0L, out.single().startMs)
        assertEquals(0L, out.single().endMs)
    }

    @Test
    fun normalizePreservesInputOrder() {
        val out = normalizeSegments(
            listOf(
                WhisperSegment(3000L, 4000L, "b"),
                WhisperSegment(1000L, 2000L, "a"),
            )
        )

        assertEquals(listOf("b", "a"), out.map { it.text })
    }

    @Test
    fun normalizeEmptyInputYieldsEmptyList() {
        assertTrue(normalizeSegments(emptyList()).isEmpty())
    }

    @Test
    fun batchesEmptyListYieldsNoBatches() {
        assertTrue(emptyList<Int>().asBatches(10).isEmpty())
    }

    @Test
    fun batchesFewerItemsThanSizeYieldsOneBatch() {
        assertEquals(listOf(listOf(1, 2)), listOf(1, 2).asBatches(10))
    }

    @Test
    fun batchesExactMultipleYieldsEvenBatches() {
        assertEquals(
            listOf(listOf(1, 2), listOf(3, 4)),
            listOf(1, 2, 3, 4).asBatches(2),
        )
    }

    @Test
    fun batchesWithRemainderKeepsTrailingBatch() {
        assertEquals(listOf(listOf(1, 2), listOf(3)), listOf(1, 2, 3).asBatches(2))
    }

    @Test
    fun batchesNonPositiveSizeYieldsSingleBatch() {
        assertEquals(listOf(listOf(1, 2, 3)), listOf(1, 2, 3).asBatches(0))
        assertEquals(listOf(listOf(1, 2, 3)), listOf(1, 2, 3).asBatches(-5))
    }
}
