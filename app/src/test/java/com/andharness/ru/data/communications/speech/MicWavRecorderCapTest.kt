package com.andharness.ru.data.communications.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the [MicWavRecorder.MAX_PCM_BYTES] cap. It must be computed in `Long`:
 * the intermediate product `16_000 * 2 * 120_000 = 3_840_000_000` exceeds [Int.MAX_VALUE], so an
 * Int computation wraps to `-454_967_296` and the `/ 1000` yields `-454_967`; the reader-loop guard
 * (`pcm.size() < MAX_PCM_BYTES`) then fails immediately, capturing zero bytes and failing every
 * dictation with `TooShortException`. The intended cap is `3_840_000` bytes (120 s of 16 kHz mono
 * PCM16).
 *
 * Pure JVM: the companion value is derived only from compile-time constants.
 */
class MicWavRecorderCapTest {

    @Test
    fun maxPcmBytesIsPositiveAndMatchesTwoMinutesOfAudio() {
        assertTrue(
            "MAX_PCM_BYTES must stay positive (Int overflow would make it negative)",
            MicWavRecorder.MAX_PCM_BYTES > 0L
        )
        assertEquals(16_000L * 2 * 120_000 / 1000, MicWavRecorder.MAX_PCM_BYTES)
        assertEquals(3_840_000L, MicWavRecorder.MAX_PCM_BYTES)
    }
}
