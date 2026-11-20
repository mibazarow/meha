package com.andharness.ru.domain.communications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [TranscriptSemanticRanker]: cosine ordering, deterministic id tie-break,
 * empty/degenerate inputs, the limit, and the lexical+semantic dedup/merge helper.
 */
class TranscriptSemanticRankerTest {

    private val query = floatArrayOf(1f, 0f)

    @Test
    fun rankOrdersByDescendingCosineAndDropsNonPositive() {
        val ranked = TranscriptSemanticRanker.rank(
            query = query,
            candidates = listOf(
                1L to floatArrayOf(0f, 1f),
                2L to floatArrayOf(1f, 1f),
                3L to floatArrayOf(1f, 0f),
            ),
            limit = 10,
        )

        assertEquals(listOf(3L, 2L), ranked.map { it.first })
    }

    @Test
    fun rankTiesBreakByAscendingId() {
        val ranked = TranscriptSemanticRanker.rank(
            query = query,
            candidates = listOf(
                7L to floatArrayOf(1f, 0f),
                3L to floatArrayOf(2f, 0f),
                5L to floatArrayOf(3f, 0f),
            ),
            limit = 10,
        )

        assertEquals(listOf(3L, 5L, 7L), ranked.map { it.first })
    }

    @Test
    fun rankHonoursLimit() {
        val ranked = TranscriptSemanticRanker.rank(
            query = query,
            candidates = listOf(
                1L to floatArrayOf(1f, 0f),
                2L to floatArrayOf(1f, 1f),
                3L to floatArrayOf(1f, 2f),
            ),
            limit = 1,
        )

        assertEquals(1, ranked.size)
        assertEquals(1L, ranked.single().first)
    }

    @Test
    fun rankEmptyOrDegenerateInputsYieldNothing() {
        assertTrue(TranscriptSemanticRanker.rank(query, emptyList(), 5).isEmpty())
        assertTrue(TranscriptSemanticRanker.rank(FloatArray(0), listOf(1L to query), 5).isEmpty())
        assertTrue(TranscriptSemanticRanker.rank(query, listOf(1L to query), 0).isEmpty())
        assertTrue(TranscriptSemanticRanker.rank(query, listOf(1L to floatArrayOf(0f, 0f)), 5).isEmpty())
    }

    @Test
    fun mergeHitsDedupsAndPutsSemanticFirst() {
        val merged = TranscriptSemanticRanker.mergeHits(
            lexicalIds = listOf(10L, 20L, 30L),
            semanticHits = listOf(30L to 0.9f, 40L to 0.8f),
            limit = 10,
        )

        assertEquals(listOf(30L, 40L, 10L, 20L), merged)
    }

    @Test
    fun mergeHitsBoundsTheUnion() {
        val merged = TranscriptSemanticRanker.mergeHits(
            lexicalIds = listOf(1L, 2L, 3L),
            semanticHits = listOf(9L to 1f),
            limit = 2,
        )

        assertEquals(listOf(9L, 1L), merged)
    }

    @Test
    fun mergeHitsEmptyLimitYieldsNothing() {
        assertTrue(TranscriptSemanticRanker.mergeHits(listOf(1L), listOf(2L to 1f), 0).isEmpty())
    }
}
