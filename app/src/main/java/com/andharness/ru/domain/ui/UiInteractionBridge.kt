package com.andharness.ru.domain.ui

/**
 * Lets a `suspend` tool request an interactive UI flow and await its result, while the Compose UI
 * observes [pending] and fulfils it. The bridge is the only shared state between the tool layer
 * (which has just the application `Context`) and the Activity-scoped UI.
 *
 * The pending request is exposed as a `StateFlow` so the UI can react without polling:
 * https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 */
interface UiInteractionBridge {
    /** The request the UI should currently fulfil, or null. */
    val pending: kotlinx.coroutines.flow.StateFlow<UiRequest?>

    /**
     * Called by a tool: publishes [request] for the UI and suspends until the UI
     * submits a result or [timeoutMs] elapses. Never throws; wraps everything in a result.
     */
    suspend fun request(request: UiRequest, timeoutMs: Long = 60_000L): UiResult

    /** Called by the UI when the interactive flow completes. */
    fun submit(result: UiResult)

    /** Called by the UI/orchestrator on cancellation. */
    fun cancel()
}
