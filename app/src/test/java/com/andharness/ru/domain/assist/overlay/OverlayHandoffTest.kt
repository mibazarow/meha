package com.andharness.ru.domain.assist.overlay

import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [OverlayHandoff] — the Android-free rules behind the BUG-041 fix.
 *
 * The rules that matter: a busy agent means the question was NOT sent (so it must be persisted and
 * the user told), and a run that ended in error/cancel must be reported honestly. The hand-off no
 * longer drops a submit just because the shared persistent chat is already in use — the orchestrator
 * answers BUSY instead and the question is persisted.
 */
class OverlayHandoffTest {

    @Test
    fun startedRunIsReportedAsStarted() {
        assertEquals(
            OverlayHandoff.Outcome.STARTED,
            OverlayHandoff.outcome(AgentRunOutcome.STARTED, AgentState.DONE),
        )
        assertEquals(
            OverlayHandoff.Outcome.STARTED,
            OverlayHandoff.outcome(AgentRunOutcome.STARTED, AgentState.RUNNING),
        )
        assertEquals(
            OverlayHandoff.Outcome.STARTED,
            OverlayHandoff.outcome(AgentRunOutcome.STARTED, AgentState.IDLE),
        )
    }

    @Test
    fun errorEndStateIsReportedAsFailed() {
        assertEquals(
            OverlayHandoff.Outcome.FAILED,
            OverlayHandoff.outcome(AgentRunOutcome.STARTED, AgentState.ERROR),
        )
    }

    @Test
    fun cancelledEndStateIsReportedAsFailed() {
        assertEquals(
            OverlayHandoff.Outcome.FAILED,
            OverlayHandoff.outcome(AgentRunOutcome.STARTED, AgentState.CANCELLED),
        )
    }

    @Test
    fun busyWinsOverAnyOtherState() {
        // A busy orchestrator returns before touching anything, so the question was not sent — the
        // state it happens to publish (e.g. RUNNING from the other turn) must never mask that.
        assertEquals(
            OverlayHandoff.Outcome.BUSY,
            OverlayHandoff.outcome(AgentRunOutcome.BUSY, AgentState.RUNNING),
        )
        assertEquals(
            OverlayHandoff.Outcome.BUSY,
            OverlayHandoff.outcome(AgentRunOutcome.BUSY, AgentState.DONE),
        )
    }
}
