package com.andharness.ru.domain.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [MessageSemanticRanker]: cosine ordering, deterministic id tie-break,
 * empty/degenerate inputs and the limit.
 */
class MessageSemanticRankerTest {

    private val query = floatArrayOf(1f, 0f)

    @Test
    fun rankOrdersByDescendingCosineAndDropsNonPositive() {
        val ranked = MessageSemanticRanker.rank(
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
        val ranked = MessageSemanticRanker.rank(
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
        val ranked = MessageSemanticRanker.rank(
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
        assertTrue(MessageSemanticRanker.rank(query, emptyList(), 5).isEmpty())
        assertTrue(MessageSemanticRanker.rank(FloatArray(0), listOf(1L to query), 5).isEmpty())
        assertTrue(MessageSemanticRanker.rank(query, listOf(1L to query), 0).isEmpty())
        assertTrue(
            MessageSemanticRanker.rank(query, listOf(1L to floatArrayOf(0f, 0f)), 5).isEmpty()
        )
    }
}
