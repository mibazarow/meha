package com.andharness.ru.domain.agent

import com.andharness.ru.data.llm.LlmTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the update.md bug #7 guarantees: an "in progress" chat state can never be a lie (so the
 * composer comes back without an app restart) and no turn can run unbounded.
 */
class RunLifecyclePolicyTest {

    @Test
    fun `active states are exactly the three working states`() {
        assertEquals(
            setOf(AgentState.RUNNING, AgentState.TOOL_EXEC, AgentState.WAITING_PERMISSION),
            RunLifecyclePolicy.ACTIVE_STATES
        )
        assertTrue(RunLifecyclePolicy.isActive(AgentState.RUNNING))
        assertTrue(RunLifecyclePolicy.isActive(AgentState.TOOL_EXEC))
        assertTrue(RunLifecyclePolicy.isActive(AgentState.WAITING_PERMISSION))
    }

    @Test
    fun `terminal states are never active`() {
        listOf(AgentState.IDLE, AgentState.DONE, AgentState.ERROR, AgentState.CANCELLED).forEach {
            assertFalse("$it must not be active", RunLifecyclePolicy.isActive(it))
        }
    }

    @Test
    fun `a running turn whose coroutine is gone is stale`() {
        assertTrue(RunLifecyclePolicy.isStale(AgentState.RUNNING, ownerJobActive = false))
        assertTrue(RunLifecyclePolicy.isStale(AgentState.TOOL_EXEC, ownerJobActive = false))
        assertTrue(RunLifecyclePolicy.isStale(AgentState.WAITING_PERMISSION, ownerJobActive = false))
    }

    @Test
    fun `a running turn with a live coroutine is not stale`() {
        assertFalse(RunLifecyclePolicy.isStale(AgentState.RUNNING, ownerJobActive = true))
    }

    @Test
    fun `a terminal state is never stale`() {
        assertFalse(RunLifecyclePolicy.isStale(AgentState.DONE, ownerJobActive = false))
        assertFalse(RunLifecyclePolicy.isStale(AgentState.CANCELLED, ownerJobActive = false))
        assertFalse(RunLifecyclePolicy.isStale(AgentState.IDLE, ownerJobActive = false))
    }

    @Test
    fun `no turn started is never overdue`() {
        assertFalse(
            RunLifecyclePolicy.isOverdue(
                startedAtMs = 0L,
                lastProgressAtMs = 0L,
                nowMs = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `a fresh turn is not overdue`() {
        assertFalse(
            RunLifecyclePolicy.isOverdue(
                startedAtMs = 1_000L,
                lastProgressAtMs = 1_000L,
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `no progress for the stall budget is overdue`() {
        val startedAt = 1_000_000L
        assertTrue(
            RunLifecyclePolicy.isOverdue(
                startedAtMs = startedAt,
                lastProgressAtMs = startedAt,
                nowMs = startedAt + RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS
            )
        )
        assertFalse(
            RunLifecyclePolicy.isOverdue(
                startedAtMs = startedAt,
                lastProgressAtMs = startedAt,
                nowMs = startedAt + RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS - 1
            )
        )
    }

    @Test
    fun `progress resets the stall budget`() {
        val startedAt = 1_000_000L
        val progressedAt = startedAt + RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS - 1
        assertFalse(
            RunLifecyclePolicy.isOverdue(
                startedAtMs = startedAt,
                lastProgressAtMs = progressedAt,
                nowMs = progressedAt + RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS - 1
            )
        )
    }

    @Test
    fun `a turn that keeps making progress still hits the hard cap`() {
        val startedAt = 1_000_000L
        val now = startedAt + RunLifecyclePolicy.RUN_HARD_CAP_MS
        assertTrue(
            RunLifecyclePolicy.isOverdue(
                startedAtMs = startedAt,
                lastProgressAtMs = now - 1,
                nowMs = now
            )
        )
    }

    @Test
    fun `stall budget is longer than every bounded phase of a turn`() {
        // tool call 4 min, permission gate 1 min.
        assertTrue(RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS > 4 * 60_000L)
        assertTrue(RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS > 60_000L)
        // the hard cap is the absolute backstop, above the stall budget.
        assertTrue(RunLifecyclePolicy.RUN_HARD_CAP_MS > RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS)
    }

    @Test
    fun `run budgets strictly exceed the LLM budgets they must not pre-empt`() {
        // The stall detector must only fire when the LLM layer itself would already have given up,
        // so it must stay strictly above the largest LLM idle/data budget.
        val maxLlmIdleMs = maxOf(
            LlmTimeouts.IDLE_PROGRESS_TIMEOUT_SEC,
            LlmTimeouts.DATA_WAIT_TIMEOUT_SEC
        ) * 1_000L
        assertTrue(
            "RUN_STALL_TIMEOUT_MS (${RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS}) must exceed the " +
                "largest LLM idle/data budget ($maxLlmIdleMs)",
            RunLifecyclePolicy.RUN_STALL_TIMEOUT_MS > maxLlmIdleMs
        )
        // The hard cap is the last-resort wall-clock cap for one agent turn and must stay strictly
        // above one completion's own overall cap.
        assertTrue(
            "RUN_HARD_CAP_MS (${RunLifecyclePolicy.RUN_HARD_CAP_MS}) must exceed " +
                "MAX_COMPLETION_SEC (${LlmTimeouts.MAX_COMPLETION_SEC} s)",
            RunLifecyclePolicy.RUN_HARD_CAP_MS > LlmTimeouts.MAX_COMPLETION_SEC * 1_000L
        )
    }

    @Test
    fun `forced stop message never claims success and tells the user to continue`() {
        val message = RunLifecyclePolicy.FORCED_STOP_MESSAGE
        assertTrue(message.contains("НЕ подтверждено"))
        assertTrue(message.contains("перезапускать не нужно"))
    }

    @Test
    fun `watchdog duration is human readable, never an epoch`() {
        // The old bug logged `elapsedMs()/1000` of an untracked turn, i.e. the epoch seconds
        // (~1790061945s). A non-positive elapsed must render as an honest zero, not a date.
        assertEquals("0 s", RunLifecyclePolicy.formatDuration(0L))
        assertEquals("0 s", RunLifecyclePolicy.formatDuration(-5L))
        assertEquals("1 s", RunLifecyclePolicy.formatDuration(1_000L))
        assertEquals("59 s", RunLifecyclePolicy.formatDuration(59_999L))
        assertEquals("1 min 0 s", RunLifecyclePolicy.formatDuration(60_000L))
        assertEquals("3 min 5 s", RunLifecyclePolicy.formatDuration(185_000L))
        assertEquals("1 h 1 min", RunLifecyclePolicy.formatDuration(3_660_000L))
    }

    @Test
    fun `watchdog duration of an epoch-sized value is not a plausible stall`() {
        // 1790061945 (epoch seconds, the reported symptom) formatted as ms would be ~20 days; the
        // formatter must at least be a duration string and never contain the raw epoch.
        val formatted = RunLifecyclePolicy.formatDuration(1_790_061_945L * 1_000L)
        assertFalse(formatted.contains("1790061945"))
        assertTrue("must be a human-readable duration, got '$formatted'", formatted.endsWith(" min"))
    }
}
