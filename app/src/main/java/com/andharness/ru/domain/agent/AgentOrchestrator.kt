package com.andharness.ru.domain.agent

import com.andharness.ru.domain.chat.ChatAttachment
import kotlinx.coroutines.flow.StateFlow

interface AgentOrchestrator {
    /**
     * Backward-compatible status of the "currently viewed" chat (the last chat an interactive run
     * was started in). New code should prefer [statusFor] together with an explicit chat id so
     * several concurrent runs stay isolated.
     */
    val status: StateFlow<AgentStatus>

    /** Status of ONE chat's run (or its idle state). Stable flow, created on first access. */
    fun statusFor(chatId: Long): StateFlow<AgentStatus>

    /**
     * Every run the orchestrator is executing or holding in its admission queue right now, for the
     * honest "what is running in the background" surface. Process-scoped: a run cannot survive a
     * process restart, so nothing is reported as active after one.
     */
    val activeRuns: StateFlow<List<ActiveRun>>

    /**
     * Starts one agent turn WITHOUT waiting for it and returns promptly. The turn is owned by the
     * ORCHESTRATOR (an application-lifetime scope), never by the caller: cancelling the caller's
     * scope — e.g. `viewModelScope` when the chat screen goes away — cannot cancel the turn. Only an
     * explicit [cancel] with the chat id stops it, and the process-lifetime foreground service keeps
     * the process alive so a background turn can finish.
     *
     * Runs are bounded by [RunConcurrencyLimiter]: an [AgentRunPriority.INTERACTIVE] turn is admitted
     * whenever a slot is free and, if all slots are busy, is QUEUED (never dropped) until a slot
     * frees. [AgentRunOutcome.BUSY] is returned only when THIS chat already has an active turn, or
     * the bounded queue itself is full; otherwise the answer is [AgentRunOutcome.STARTED].
     *
     * Cancelling a launched turn's caller does NOT cancel the turn. Honesty: a PROCESS kill (low
     * memory, crash, user swipe-away) does end it — nothing here can survive process death.
     */
    fun start(
        chatId: Long,
        userMessage: String,
        attachments: List<ChatAttachment> = emptyList(),
        priority: AgentRunPriority = AgentRunPriority.INTERACTIVE
    ): AgentRunOutcome

    /**
     * Runs one agent turn and suspends until it completes, for callers that need the final state
     * (scheduled tasks, objectives, the overlay). The turn itself still runs on the orchestrator's
     * application-lifetime scope, so cancelling the caller stops the WAIT, not the turn; use
     * [cancel] with the chat id to actually stop it. [attachments] are files already copied to a
     * stable local path (§19).
     *
     * The same [RunConcurrencyLimiter] contract as [start] applies; BUSY is only ever returned when
     * this chat is already running or the bounded queue is full.
     */
    suspend fun run(
        chatId: Long,
        userMessage: String,
        attachments: List<ChatAttachment> = emptyList(),
        priority: AgentRunPriority = AgentRunPriority.INTERACTIVE
    ): AgentRunOutcome

    /** Cancels the run of the currently viewed chat, if any (backward-compatible shortcut). */
    fun cancel()

    /** Cancels exactly the run owned by [chatId] (running or queued); no-op when there is none. */
    fun cancel(chatId: Long)

    /** Grants the pending permission of the currently viewed chat's run, if any. */
    fun grantPermission()

    /** Grants the pending permission of [chatId]'s run, if any. */
    fun grantPermission(chatId: Long)

    /** Denies the pending permission of the currently viewed chat's run, if any. */
    fun denyPermission()

    /** Denies the pending permission of [chatId]'s run, if any. */
    fun denyPermission(chatId: Long)
}
