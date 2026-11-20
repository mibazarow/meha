package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порог выбора FTS-шортлиста: маленькая база памяти обязана идти прежним полным
 * сканом, иначе поведение recall изменилось бы. Порог чистый, поэтому проверяется без Room.
 */
class MemoryCandidateSelectorTest {

    @Test
    fun smallTableNeverUsesFts() {
        assertFalse(MemoryCandidateSelector.shouldUseFts(0))
        assertFalse(MemoryCandidateSelector.shouldUseFts(1))
        assertFalse(MemoryCandidateSelector.shouldUseFts(MemoryCandidateSelector.FTS_THRESHOLD))
    }

    @Test
    fun tableAboveThresholdUsesFts() {
        assertTrue(MemoryCandidateSelector.shouldUseFts(MemoryCandidateSelector.FTS_THRESHOLD + 1))
        assertTrue(MemoryCandidateSelector.shouldUseFts(1000))
    }

    @Test
    fun smallTableFallsBackToFullScan() {
        assertTrue(MemoryCandidateSelector.shouldFallBackToFullScan(memoryCount = 5, ftsCandidateCount = 5))
        assertTrue(
            MemoryCandidateSelector.shouldFallBackToFullScan(
                memoryCount = MemoryCandidateSelector.FTS_THRESHOLD,
                ftsCandidateCount = 100
            )
        )
    }

    @Test
    fun largeTableWithNoCandidatesFallsBack() {
        assertTrue(MemoryCandidateSelector.shouldFallBackToFullScan(memoryCount = 1000, ftsCandidateCount = 0))
    }

    @Test
    fun largeTableWithCandidatesKeepsFtsShortlist() {
        assertFalse(MemoryCandidateSelector.shouldFallBackToFullScan(memoryCount = 1000, ftsCandidateCount = 3))
    }
}
