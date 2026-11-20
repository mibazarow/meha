package com.andharness.ru.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure cadence math of [StreamingReveal]: the typewriter reveal reveals the
 * buffered streaming backlog at a steady speed, clamped so it is never sub-frame and can never lag
 * by more than the catch-up ceiling.
 */
class StreamingRevealTest {

    @Test
    fun nothingToRevealYieldsNoAnimation() {
        assertEquals(0, StreamingReveal.revealDurationMs(0))
        assertEquals(0, StreamingReveal.revealDurationMs(-7))
    }

    @Test
    fun singleCharacterStillGetsAVisibleStep() {
        // 1 char at 90 cps would be ~11 ms (sub-frame) → lifted to the floor.
        assertEquals(StreamingReveal.MIN_DURATION_MS, StreamingReveal.revealDurationMs(1))
    }

    @Test
    fun steadyCadenceScalesWithTheBacklog() {
        // 9 chars at 90 cps == 100 ms exactly.
        assertEquals(100, StreamingReveal.revealDurationMs(9))
        // 18 chars == 200 ms.
        assertEquals(200, StreamingReveal.revealDurationMs(18))
    }

    @Test
    fun largeBacklogIsCappedAtTheCatchUpCeiling() {
        // 63 chars at 90 cps == exactly the ceiling.
        assertEquals(StreamingReveal.MAX_DURATION_MS, StreamingReveal.revealDurationMs(63))
        assertEquals(StreamingReveal.MAX_DURATION_MS, StreamingReveal.revealDurationMs(1000))
    }

    @Test
    fun durationNeverDecreasesAsTheBacklogGrows() {
        var previous = 0
        for (chars in 0..200) {
            val duration = StreamingReveal.revealDurationMs(chars)
            assertTrue("duration must not decrease", duration >= previous)
            assertTrue(duration in 0..StreamingReveal.MAX_DURATION_MS)
            previous = duration
        }
    }
}
