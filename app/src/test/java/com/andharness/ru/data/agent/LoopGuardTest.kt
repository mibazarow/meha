package com.andharness.ru.data.agent

import com.andharness.ru.domain.agent.LoopGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the progress-aware anti-loop guard (BUG-045).
 *
 * The guard must treat a repeated tool batch as a stuck loop ONLY when BOTH the call signature AND
 * the observable progress signature (result success + short digest) are identical to the previous
 * iteration. A changing result (on↔off, a counter, fresh sensor data) is progress and resets the
 * counter, so legitimate repetition is never killed. A repeated call that produces the SAME result
 * again — including a repeated ERROR — is a genuine loop and is aborted after the threshold.
 */
class LoopGuardTest {

    private fun guard(max: Int = LoopGuard.DEFAULT_MAX_IDENTICAL_ITERATIONS) =
        LoopGuard(max)

    /** The very first iteration is never a loop, whatever the inputs. */
    @Test
    fun `first iteration is never a loop`() {
        val g = guard()
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on"))
    }

    /**
     * BUG-045 core: the SAME call repeated, but the RESULT changes each time (on→off→on, like SOS,
     * a counter, or fresh sensor data). This is progress, so the guard must never abort.
     */
    @Test
    fun `identical call but changing result is progress, never a loop`() {
        val g = guard()
        // Same tool + same arguments, but the observable world changes every time.
        assertFalse(g.record("flashlight_set:{\"state\":\"on\"}", "flashlight_set:true:on"))
        assertFalse(g.record("flashlight_set:{\"state\":\"on\"}", "flashlight_set:true:off"))
        assertFalse(g.record("flashlight_set:{\"state\":\"on\"}", "flashlight_set:true:on"))
        assertFalse(g.record("flashlight_set:{\"state\":\"on\"}", "flashlight_set:true:off"))
        assertEquals(1, g.consecutiveRepeats)
    }

    /**
     * A counter that is incremented with the same call: the call signature is identical but the
     * result digest grows (1, 2, 3). Progress, not a loop.
     */
    @Test
    fun `identical call with a growing counter result is progress`() {
        val g = guard()
        assertFalse(g.record("counter_increment", "counter_increment:true:1"))
        assertFalse(g.record("counter_increment", "counter_increment:true:2"))
        assertFalse(g.record("counter_increment", "counter_increment:true:3"))
        assertFalse(g.record("counter_increment", "counter_increment:true:4"))
        assertEquals(1, g.consecutiveRepeats)
    }

    /**
     * BUG-045 core: identical call AND identical result repeated for the threshold is a genuine
     * stuck loop and must abort exactly at the threshold (not before, not one late).
     */
    @Test
    fun `identical call and identical result aborts at the threshold`() {
        val g = guard(max = 3)
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on")) // 1
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on")) // 2
        assertTrue(g.record("flashlight_set:on", "flashlight_set:true:on"))  // 3 -> stuck
        assertEquals(3, g.consecutiveRepeats)
    }

    /** A repeated call that keeps producing the SAME error is a real loop and must trigger. */
    @Test
    fun `identical call and identical error result triggers the loop`() {
        val g = guard(max = 3)
        val err = "wallpaper_set:false:Не удалось установить обои: нет разрешения SET_WALLPAPER."
        assertFalse(g.record("wallpaper_set", err)) // 1
        assertFalse(g.record("wallpaper_set", err)) // 2
        assertTrue(g.record("wallpaper_set", err))  // 3 -> stuck
    }

    /** One differing result in the middle breaks the streak and resets the counter. */
    @Test
    fun `a single changed result resets the repeat counter`() {
        val g = guard(max = 3)
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on")) // 1
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on")) // 2
        // Different result this time -> progress, counter resets to 1.
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:off"))
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on")) // 1 again
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on")) // 2
        assertEquals(2, g.consecutiveRepeats)
    }

    /** A different call signature is never a repeat, even with an identical result. */
    @Test
    fun `a different call signature is never a repeat`() {
        val g = guard(max = 3)
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on"))
        assertFalse(g.record("flashlight_set:off", "flashlight_set:true:on"))
        assertEquals(1, g.consecutiveRepeats)
    }

    /** A fresh turn clears all state. */
    @Test
    fun `reset clears state`() {
        val g = guard(max = 3)
        g.record("flashlight_set:on", "flashlight_set:true:on")
        g.record("flashlight_set:on", "flashlight_set:true:on")
        g.reset()
        assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on"))
        assertEquals(1, g.consecutiveRepeats)
    }

    /**
     * NEW_TASKS §5.1: a three-step cycle (A→B→C→A→B→C) is a loop even though no iteration is
     * identical to the one before it. It must stop at the second full repetition.
     */
    @Test
    fun `a three-step cycle is a loop`() {
        val g = guard(max = 3)
        assertFalse(g.record("screen_a", "screen_a:true:same"))
        assertFalse(g.record("screen_b", "screen_b:true:same"))
        assertFalse(g.record("screen_c", "screen_c:true:same"))
        assertFalse(g.record("screen_a", "screen_a:true:same"))
        assertFalse(g.record("screen_b", "screen_b:true:same"))
        assertTrue("the second full repetition is a loop", g.record("screen_c", "screen_c:true:same"))
        assertEquals(3, g.detectedCycle)
    }

    /** Two-step alternation with stable per-step results stays legitimate (flashlight on↔off). */
    @Test
    fun `two-step alternation is never a cycle`() {
        val g = guard(max = 3)
        repeat(10) {
            assertFalse(g.record("flashlight_set:on", "flashlight_set:true:on"))
            assertFalse(g.record("flashlight_set:off", "flashlight_set:true:off"))
        }
        assertEquals(0, g.detectedCycle)
    }

    /** A three-step pattern whose results change is progress, not a cycle. */
    @Test
    fun `a three-step pattern with changing results is progress`() {
        val g = guard(max = 3)
        var n = 0
        repeat(3) {
            assertFalse(g.record("a", "a:true:${n++}"))
            assertFalse(g.record("b", "b:true:${n++}"))
            assertFalse(g.record("c", "c:true:${n++}"))
        }
        assertEquals(0, g.detectedCycle)
    }

    /** A custom threshold is honoured (e.g. a stricter guard for a narrower tool set). */
    @Test
    fun `honours a custom threshold`() {
        val g = guard(max = 2)
        assertFalse(g.record("spin", "spin:true:x")) // 1
        assertTrue(g.record("spin", "spin:true:x"))  // 2 -> stuck
    }
}
