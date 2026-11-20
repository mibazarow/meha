package com.andharness.ru.data.tools.communications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure `messenger_voice_capture` argument helper: the `duration_seconds` default
 * and the 5..300 clamp (the capture service has its own, separate hard cap).
 */
class MessengerVoiceCaptureSupportTest {

    @Test
    fun missingValueUsesDefault() {
        assertEquals(DEFAULT_CAPTURE_SECONDS, clampCaptureSeconds(null))
        assertEquals(DEFAULT_CAPTURE_SECONDS, clampCaptureSeconds(""))
        assertEquals(DEFAULT_CAPTURE_SECONDS, clampCaptureSeconds("not-a-number"))
    }

    @Test
    fun valueInRangeIsKept() {
        assertEquals(30L, clampCaptureSeconds("30"))
        assertEquals(120L, clampCaptureSeconds(" 120 "))
    }

    @Test
    fun valueBelowMinimumIsClampedUp() {
        assertEquals(MIN_CAPTURE_SECONDS, clampCaptureSeconds("1"))
        assertEquals(MIN_CAPTURE_SECONDS, clampCaptureSeconds("0"))
        assertEquals(MIN_CAPTURE_SECONDS, clampCaptureSeconds("-5"))
    }

    @Test
    fun valueAboveMaximumIsClampedDown() {
        assertEquals(MAX_CAPTURE_SECONDS, clampCaptureSeconds("9999"))
        assertEquals(MAX_CAPTURE_SECONDS, clampCaptureSeconds("301"))
    }
}
