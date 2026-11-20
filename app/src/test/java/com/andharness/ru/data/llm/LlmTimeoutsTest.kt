package com.andharness.ru.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the LLM streaming budgets and their invariants.
 *
 * The authoritative bounds must stay mutually consistent: the no-progress (idle) watchdog is not
 * allowed to fire before the OkHttp readTimeout / response budget it backs, and the overall
 * wall-clock cap must strictly exceed every response budget so it can never pre-empt them.
 */
class LlmTimeoutsTest {

    @Test
    fun configuredBudgets() {
        assertEquals(1024L, LlmTimeouts.DATA_WAIT_TIMEOUT_SEC)
        assertEquals(1024L, LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC)
        assertEquals(3600L, LlmTimeouts.MAX_COMPLETION_SEC)
    }

    @Test
    fun idleWatchdogDoesNotFireBeforeTheResponseBudget() {
        assertTrue(
            "the no-progress watchdog must not abort before the read budget is exhausted",
            LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC >= LlmTimeouts.DATA_WAIT_TIMEOUT_SEC
        )
    }

    @Test
    fun overallCapStrictlyExceedsEveryResponseBudget() {
        assertTrue(
            "MAX_COMPLETION_SEC must strictly exceed DATA_WAIT_TIMEOUT_SEC",
            LlmTimeouts.MAX_COMPLETION_SEC > LlmTimeouts.DATA_WAIT_TIMEOUT_SEC
        )
    }
}
