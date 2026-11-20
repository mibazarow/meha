package com.andharness.ru.domain.objective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honest gating of background objective runs (checklist §2, §5): no run for terminal/paused/blocked
 * objectives, no run without an LLM, no run past the budget, and deferral by the power guard.
 */
class ObjectiveRunGateTest {

    private fun decide(
        status: ObjectiveStatus = ObjectiveStatus.ACTIVE,
        blocked: Boolean = false,
        llmConfigured: Boolean = true,
        deferred: Boolean = false,
        backgroundRuns: Int = 0,
        maxRuns: Int = ObjectiveRunGate.MAX_BACKGROUND_RUNS
    ) = ObjectiveRunGate.evaluate(status, blocked, llmConfigured, deferred, backgroundRuns, maxRuns)

    @Test
    fun `active objective with a provider is runnable`() {
        assertEquals(ObjectiveRunOutcome.RUNNABLE, decide())
    }

    @Test
    fun `terminal objective is never run`() {
        assertEquals(ObjectiveRunOutcome.TERMINAL, decide(status = ObjectiveStatus.DONE))
        assertEquals(ObjectiveRunOutcome.TERMINAL, decide(status = ObjectiveStatus.CANCELLED))
    }

    @Test
    fun `paused objective is not auto-run`() {
        assertEquals(ObjectiveRunOutcome.PAUSED, decide(status = ObjectiveStatus.PAUSED))
    }

    @Test
    fun `blocked objective is not auto-run`() {
        assertEquals(ObjectiveRunOutcome.BLOCKED, decide(blocked = true))
    }

    @Test
    fun `budget exhaustion blocks further background runs`() {
        assertEquals(
            ObjectiveRunOutcome.BUDGET_EXHAUSTED,
            decide(backgroundRuns = ObjectiveRunGate.MAX_BACKGROUND_RUNS)
        )
        assertEquals(ObjectiveRunOutcome.RUNNABLE, decide(backgroundRuns = ObjectiveRunGate.MAX_BACKGROUND_RUNS - 1))
    }

    @Test
    fun `no llm is an honest refusal rather than fake progress`() {
        assertEquals(ObjectiveRunOutcome.NO_LLM, decide(llmConfigured = false))
    }

    @Test
    fun `power guard defers non-essential work`() {
        assertEquals(ObjectiveRunOutcome.DEFERRED, decide(deferred = true))
    }

    @Test
    fun `terminal wins over every other rule`() {
        assertEquals(
            ObjectiveRunOutcome.TERMINAL,
            decide(status = ObjectiveStatus.DONE, blocked = true, llmConfigured = false, deferred = true)
        )
    }

    @Test
    fun `isRunnable mirrors evaluate`() {
        assertTrue(ObjectiveRunGate.isRunnable(ObjectiveStatus.ACTIVE, false, true, false, 0))
        assertFalse(ObjectiveRunGate.isRunnable(ObjectiveStatus.PAUSED, false, true, false, 0))
    }
}
