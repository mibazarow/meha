package com.andharness.ru.domain.agent

/**
 * One run that the orchestrator is currently working on or holding in its admission queue, for the
 * honest "what is running right now" surface (SPEC §20 visibility).
 *
 * [queued] distinguishes "actually being executed" from "accepted but waiting for a slot": a queued
 * INTERACTIVE run is never rejected, it is simply waiting for a slot to free, and the user must be
 * able to see that instead of a silent BUSY.
 */
data class ActiveRun(
    val chatId: Long,
    /** Chat title, or the first line of the request when the chat is still untitled. */
    val title: String,
    val kind: AgentRunPriority,
    val startedAtMs: Long,
    val queued: Boolean
)
