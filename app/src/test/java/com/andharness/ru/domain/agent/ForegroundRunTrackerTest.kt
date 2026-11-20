package com.andharness.ru.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the shared foreground service's lifetime under CONCURRENT runs.
 *
 * The defect this pins: with a per-chat registry several turns can run at once, but there is one
 * `AgentForegroundService`. If the first finished turn stopped the service while a sibling was still
 * generating, the sibling would lose its foreground protection and the OS could kill the process (and
 * the turn). [ForegroundRunTracker] keeps the service alive until the last turn leaves.
 */
class ForegroundRunTrackerTest {

    @Test
    fun `the service must stay up while another chat still runs`() {
        val tracker = ForegroundRunTracker()
        assertTrue(tracker.add(1L))
        assertTrue(tracker.add(2L))
        assertEquals(2, tracker.size())

        // First run finishes: another chat is still active, so the service must NOT be stopped.
        assertFalse("a sibling run is still active — stop the service only later", tracker.remove(1L))
        assertEquals(1, tracker.size())

        // Last run finishes: now the service may be stopped.
        assertTrue("no run remains — the service may be stopped", tracker.remove(2L))
        assertEquals(0, tracker.size())
    }

    @Test
    fun `adding the same chat twice does not keep the service alive forever`() {
        val tracker = ForegroundRunTracker()
        assertTrue(tracker.add(7L))
        assertFalse("re-adding an already registered chat is idempotent", tracker.add(7L))
        assertEquals(1, tracker.size())
        assertTrue(tracker.remove(7L))
        assertEquals(0, tracker.size())
    }

    @Test
    fun `an unknown chat removal stops only when nothing else holds the service`() {
        val tracker = ForegroundRunTracker()
        // Empty registry: an (unexpected) removal still resolves to a stop.
        assertTrue(tracker.remove(99L))

        tracker.add(1L)
        // Removing a chat that is not registered must not stop the service held by chat 1.
        assertFalse(tracker.remove(99L))
        assertEquals(1, tracker.size())
    }
}
