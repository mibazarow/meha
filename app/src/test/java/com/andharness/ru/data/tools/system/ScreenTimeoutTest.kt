package com.andharness.ru.data.tools.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [screenTimeoutMillisFromSeconds] / [screenTimeoutClamped]. Pure Long/Int math,
 * no Android runtime.
 *
 * `Settings.System.SCREEN_OFF_TIMEOUT` is an int in milliseconds, so any request above
 * [Int.MAX_VALUE] ms must be clamped rather than overflowed to a negative value:
 * https://developer.android.com/reference/android/provider/Settings.System#SCREEN_OFF_TIMEOUT
 */
class ScreenTimeoutTest {

    @Test
    fun typicalValuesConvertExactly() {
        assertEquals(1_000, screenTimeoutMillisFromSeconds(1))
        assertEquals(60_000, screenTimeoutMillisFromSeconds(60))
        assertEquals(1_800_000, screenTimeoutMillisFromSeconds(1_800))
    }

    @Test
    fun thirtyDaysIsClampedToIntMaxInsteadOfOverflowing() {
        assertEquals(Int.MAX_VALUE, screenTimeoutMillisFromSeconds(2_592_000L))
        assertTrue(screenTimeoutClamped(2_592_000L))
    }

    @Test
    fun intMaxSecondsIsClampedToIntMax() {
        assertEquals(Int.MAX_VALUE, screenTimeoutMillisFromSeconds(Int.MAX_VALUE.toLong()))
        assertTrue(screenTimeoutClamped(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun largestFittingSecondValueIsNotClamped() {
        assertEquals(2_147_483_000, screenTimeoutMillisFromSeconds(MAX_SCREEN_TIMEOUT_SECONDS))
        assertFalse(screenTimeoutClamped(MAX_SCREEN_TIMEOUT_SECONDS))
    }

    @Test
    fun oneSecondOverTheLimitIsClamped() {
        assertEquals(Int.MAX_VALUE, screenTimeoutMillisFromSeconds(MAX_SCREEN_TIMEOUT_SECONDS + 1))
        assertTrue(screenTimeoutClamped(MAX_SCREEN_TIMEOUT_SECONDS + 1))
    }

    @Test
    fun hugeValuesDoNotOverflowLongMultiplication() {
        assertEquals(Int.MAX_VALUE, screenTimeoutMillisFromSeconds(Long.MAX_VALUE / 1000L))
        assertEquals(Int.MAX_VALUE, screenTimeoutMillisFromSeconds(Long.MAX_VALUE))
    }

    @Test
    fun nonPositiveBecomesOneMillisecondAndIsNotReportedAsClamped() {
        assertEquals(1, screenTimeoutMillisFromSeconds(0L))
        assertEquals(1, screenTimeoutMillisFromSeconds(-5L))
        assertFalse(screenTimeoutClamped(0L))
        assertFalse(screenTimeoutClamped(-5L))
    }
}
