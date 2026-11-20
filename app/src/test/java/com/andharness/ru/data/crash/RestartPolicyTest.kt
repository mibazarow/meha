package com.andharness.ru.data.crash

import com.andharness.ru.domain.crash.RestartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the crash-restart loop guard (update.md §1.2): the per-hour limit, the
 * exponential backoff ladder, and the two window-reset rules (expired window / healthy uptime).
 */
class RestartPolicyTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun firstCrashInAFreshWindowIsAllowedWithTheFirstBackoff() {
        val decision = RestartPolicy.onCrash(RestartState(), t0)

        assertTrue(decision.allowed)
        assertFalse(decision.limitReached)
        assertEquals(1, decision.restartNumber)
        assertEquals(5_000L, decision.delayMs)
        assertEquals(1, decision.state.restartCount)
        assertEquals(t0, decision.state.windowStartAt)
        assertEquals(t0, decision.state.lastRestartAt)
    }

    @Test
    fun backoffGrowsExponentiallyWithinTheWindow() {
        val first = RestartPolicy.onCrash(RestartState(), t0)
        val second = RestartPolicy.onCrash(first.state, t0 + 1_000)
        val third = RestartPolicy.onCrash(second.state, t0 + 2_000)

        assertEquals(5_000L, first.delayMs)
        assertEquals(30_000L, second.delayMs)
        assertEquals(120_000L, third.delayMs)
        assertEquals(2, second.restartNumber)
        assertEquals(3, third.restartNumber)
        assertTrue(third.allowed)
        // The window did not restart: its start stays at the very first crash.
        assertEquals(t0, third.state.windowStartAt)
    }

    @Test
    fun fourthCrashInsideTheWindowStopsRestarting() {
        var state = RestartPolicy.onCrash(RestartState(), t0).state
        state = RestartPolicy.onCrash(state, t0 + 1_000).state
        state = RestartPolicy.onCrash(state, t0 + 2_000).state

        val fourth = RestartPolicy.onCrash(state, t0 + 3_000)

        assertFalse(fourth.allowed)
        assertTrue(fourth.limitReached)
        assertEquals(0L, fourth.delayMs)
        assertEquals(RestartPolicy.MAX_RESTARTS_PER_WINDOW + 1, fourth.restartNumber)
        assertEquals(RestartPolicy.MAX_RESTARTS_PER_WINDOW + 1, fourth.state.restartCount)
    }

    @Test
    fun windowResetsAfterOneHour() {
        var state = RestartPolicy.onCrash(RestartState(), t0).state
        state = RestartPolicy.onCrash(state, t0 + 1_000).state
        state = RestartPolicy.onCrash(state, t0 + 2_000).state
        val blocked = RestartPolicy.onCrash(state, t0 + 3_000)
        assertFalse(blocked.allowed)

        val afterWindow = RestartPolicy.onCrash(blocked.state, t0 + RestartPolicy.WINDOW_MS + 1)

        assertTrue(afterWindow.allowed)
        assertEquals(1, afterWindow.restartNumber)
        assertEquals(5_000L, afterWindow.delayMs)
        assertEquals(t0 + RestartPolicy.WINDOW_MS + 1, afterWindow.state.windowStartAt)
    }

    @Test
    fun windowExpiresExactlyAtTheBoundary() {
        val state = RestartState(restartCount = 3, windowStartAt = t0, lastRestartAt = t0)
        assertFalse(RestartPolicy.isWindowExpired(state, t0 + RestartPolicy.WINDOW_MS - 1))
        assertTrue(RestartPolicy.isWindowExpired(state, t0 + RestartPolicy.WINDOW_MS))
    }

    @Test
    fun healthyUptimeResetsTheCounterEvenInsideTheWindow() {
        val previous = RestartState(
            restartCount = 2,
            windowStartAt = t0,
            lastRestartAt = t0 + 1_000
        )
        val healthyNow = t0 + 1_000 + RestartPolicy.HEALTHY_UPTIME_MS

        val decision = RestartPolicy.onCrash(previous, healthyNow)

        assertTrue(decision.allowed)
        assertEquals(1, decision.restartNumber)
        assertEquals(5_000L, decision.delayMs)
        assertEquals(healthyNow, decision.state.windowStartAt)
    }

    @Test
    fun crashJustBeforeHealthyUptimeDoesNotResetTheCounter() {
        val previous = RestartState(
            restartCount = 2,
            windowStartAt = t0,
            lastRestartAt = t0 + 1_000
        )
        val almostHealthy = t0 + 1_000 + RestartPolicy.HEALTHY_UPTIME_MS - 1

        val decision = RestartPolicy.onCrash(previous, almostHealthy)

        assertEquals(3, decision.restartNumber)
        assertEquals(t0, decision.state.windowStartAt)
    }

    @Test
    fun backoffLadderIsClampedToItsBounds() {
        assertEquals(5_000L, RestartPolicy.backoffMs(0))
        assertEquals(5_000L, RestartPolicy.backoffMs(1))
        assertEquals(30_000L, RestartPolicy.backoffMs(2))
        assertEquals(120_000L, RestartPolicy.backoffMs(3))
        assertEquals(120_000L, RestartPolicy.backoffMs(99))
    }

    @Test
    fun limitConstantMatchesTheDocumentedThreePerHour() {
        assertEquals(3, RestartPolicy.MAX_RESTARTS_PER_WINDOW)
        assertEquals(60L * 60 * 1000, RestartPolicy.WINDOW_MS)
        assertEquals(5L * 60 * 1000, RestartPolicy.HEALTHY_UPTIME_MS)
    }
}
