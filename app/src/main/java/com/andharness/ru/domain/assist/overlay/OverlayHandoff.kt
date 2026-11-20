package com.andharness.ru.domain.assist.overlay

import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentState

/**
 * BUG-041: the pure decisions of the floating-assistant hand-off, kept free of Android types so they
 * are unit-testable on the JVM.
 *
 * The bubble used to depend on launching `MainActivity`: the chat was created, the question was
 * parked in the in-memory [com.andharness.ru.inbound.InboundSeedStore], and only the
 * `ChatViewModel` that the chat screen constructs consumed that seed. With no Activity in front of
 * the user, a background activity launch is not guaranteed
 * (https://developer.android.com/guide/components/activities/secure-bal), so the seed was never
 * consumed and no agent turn ever ran. The overlay now runs the turn directly through the same
 * [com.andharness.ru.domain.agent.AgentOrchestrator.run] entry point scheduled tasks use, and this
 * object holds the rule that make that safe.
 */
object OverlayHandoff {

    /** What the overlay must report once the agent call returned. */
    enum class Outcome {
        /** The turn is under way (the orchestrator persists its own honest error/cancel note). */
        STARTED,

        /** No turn was started because another run is already active; the question was NOT sent. */
        BUSY,

        /** The turn ended in an error or was cancelled. */
        FAILED,
    }

    /**
     * Maps the orchestrator's result to what the user must be told.
     *
     * [AgentRunOutcome.BUSY] is called out separately from a run that started and failed: with BUSY
     * the question was never handed to an agent (only one run can be active at a time), so its text
     * still has to be persisted. A run that started either leaves the orchestrator in a terminal
     * state it already annotated in the chat (`ERROR` / `CANCELLED`), or ends `DONE`.
     */
    fun outcome(runOutcome: AgentRunOutcome, state: AgentState): Outcome = when {
        runOutcome == AgentRunOutcome.BUSY -> Outcome.BUSY
        state == AgentState.ERROR || state == AgentState.CANCELLED -> Outcome.FAILED
        else -> Outcome.STARTED
    }
}
