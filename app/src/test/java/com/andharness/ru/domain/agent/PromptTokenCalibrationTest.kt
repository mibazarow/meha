package com.andharness.ru.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Estimate-vs-actual prompt-size calibration (CONTEXT_TASKS_V2). */
class PromptTokenCalibrationTest {

    @Test
    fun recordsTheRatioAndAveragesTheWindow() {
        val calibration = PromptTokenCalibration(windowSize = 3)

        assertEquals(1.0, calibration.record(estimated = 100, actual = 100)!!, 1e-9)
        assertEquals(1.2, calibration.record(estimated = 120, actual = 100)!!, 1e-9)
        assertEquals(0.8, calibration.record(estimated = 80, actual = 100)!!, 1e-9)

        assertEquals(1.0, calibration.averageRatio()!!, 1e-9)
        assertFalse("a mean of 1.0 is not a divergence", calibration.diverges())
    }

    @Test
    fun theWindowKeepsOnlyTheMostRecentSamples() {
        val calibration = PromptTokenCalibration(windowSize = 2)

        calibration.record(estimated = 300, actual = 100) // 3.0, evicted
        calibration.record(estimated = 100, actual = 100) // 1.0
        calibration.record(estimated = 110, actual = 100) // 1.1

        assertEquals(2, calibration.samples().size)
        assertEquals(1.05, calibration.averageRatio()!!, 1e-9)
    }

    @Test
    fun unknownUsageIsNotRecordedAsZero() {
        val calibration = PromptTokenCalibration()

        assertNull("no provider usage → no ratio", calibration.record(estimated = 100, actual = 0))
        assertNull("empty estimate → no ratio", calibration.record(estimated = 0, actual = 100))
        assertNull(calibration.averageRatio())
        assertFalse(calibration.diverges())
    }

    @Test
    fun aConsistentMismatchIsFlaggedAsDivergence() {
        val calibration = PromptTokenCalibration()

        // The estimate under-counts by 30% on every request — exactly the case worth fixing.
        repeat(5) { calibration.record(estimated = 70, actual = 100) }

        assertEquals(0.7, calibration.averageRatio()!!, 1e-9)
        assertTrue(calibration.diverges())
    }
}
