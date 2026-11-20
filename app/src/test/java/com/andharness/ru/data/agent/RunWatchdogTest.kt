package com.andharness.ru.data.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the `1790061945s` watchdog log: [RunWatchdog.elapsedMs] must be a real
 * duration, never the current epoch. It is called by the orchestrator's force-stop path, which is
 * also reached when a stale published state is reset before any watchdog ever started — in that case
 * the honest answer is `0`, not `System.currentTimeMillis()`.
 */
class RunWatchdogTest {

    private fun watchdog() = RunWatchdog(
        scope = CoroutineScope(Dispatchers.Unconfined),
        isRunActive = { false },
        onOverdue = {}
    )

    @Test
    fun `elapsed is zero before any turn is tracked, never an epoch`() {
        assertEquals(0L, watchdog().elapsedMs())
    }

    @Test
    fun `elapsed is zero after a tracked turn is stopped`() {
        val watchdog = watchdog()
        watchdog.start()
        watchdog.stop()
        assertEquals(0L, watchdog.elapsedMs())
    }

    @Test
    fun `elapsed is a small real duration while a turn is tracked`() {
        val watchdog = watchdog()
        watchdog.start()
        val elapsed = watchdog.elapsedMs()
        assertTrue("expected a small duration, got $elapsed", elapsed in 0L..5_000L)
        watchdog.stop()
    }

    /**
     * BUG-046 regression: a run that is ACTIVE but parked on a legitimate EXTERNAL wait — a system
     * UI (wallpaper picker), a network round-trip, a tool executing — must NOT be mistaken for a
     * stall. The stall budget is 18 min, so even a run that emits NO progress marker at all for a
     * few seconds must keep the watchdog quiet. (A real stall is only after the full budget, which
     * `RunLifecyclePolicyTest` covers separately.)
     */
    @Test
    fun `does not fire for an active run waiting on an external step within tolerance`() =
        runBlocking {
            var overdue = false
            val w = RunWatchdog(
                scope = CoroutineScope(Dispatchers.Default),
                isRunActive = { true }, // active, but idle on an external step (no progress markers)
                onOverdue = { overdue = true }
            )
            w.start()
            // One full watchdog tick (15 s) plus margin — far below the 18-min stall budget, so a
            // run parked on an external step for this long must never be treated as a stall.
            delay(1_700)
            w.stop()
            assertFalse("a short external wait must not be treated as a stall", overdue)
        }
}
