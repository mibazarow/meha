package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The opinion confidence prior in [MemoryRanker.prior]: it applies only to `kind = "opinion"`,
 * decays with age, and leaves non-opinion records' scores exactly as before.
 */
class MemoryRankerOpinionTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test
    fun higherConfidenceWinsWhenEverythingElseIsEqual() {
        val opinions = listOf(
            MemoryRecord(
                id = 1L,
                text = "Люблю шоколад",
                tags = "еда",
                kind = "opinion",
                confidence = 0.9f,
                createdAt = now
            ),
            MemoryRecord(
                id = 2L,
                text = "Люблю шоколад",
                tags = "еда",
                kind = "opinion",
                confidence = 0.2f,
                createdAt = now
            )
        )

        val ranked = MemoryRanker.rank("шоколад", opinions, now = now)

        assertEquals("the stronger stance must rank first", 1L, ranked.first().id)
    }

    @Test
    fun staleOpinionLosesToFreshOpinion() {
        val opinions = listOf(
            MemoryRecord(
                id = 1L,
                text = "Люблю шоколад",
                tags = "еда",
                kind = "opinion",
                confidence = 0.9f,
                createdAt = now - 365 * day
            ),
            MemoryRecord(
                id = 2L,
                text = "Люблю шоколад",
                tags = "еда",
                kind = "opinion",
                confidence = 0.5f,
                createdAt = now
            )
        )

        val ranked = MemoryRanker.rank("шоколад", opinions, now = now)

        assertEquals("the fresh stance must outrank the decayed one", 2L, ranked.first().id)
    }

    @Test
    fun nonOpinionConfidenceDoesNotChangeTheScore() {
        val records = listOf(
            MemoryRecord(id = 1L, text = "Любит кофе", kind = "memory", confidence = 0.0f, createdAt = now),
            MemoryRecord(id = 2L, text = "Любит кофе", kind = "memory", confidence = 1.0f, createdAt = now)
        )

        val ranked = MemoryRanker.rank("кофе", records, now = now)
        val low = ranked.first { it.id == 1L }.score
        val high = ranked.first { it.id == 2L }.score

        assertEquals(
            "the confidence prior must be gated on opinion records",
            low,
            high,
            1e-9
        )
    }
}
