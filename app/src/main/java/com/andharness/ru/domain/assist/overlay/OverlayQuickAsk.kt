package com.andharness.ru.domain.assist.overlay

/**
 * Android-free reducer for [OverlayQuickAskState].
 *
 * The panel is a tiny state machine, and the rules that matter (blank input never submits, a
 * submit in flight cannot be submitted twice, a failure keeps the typed text so the user does not
 * lose it) are cheaper to pin down with JVM tests than on a device.
 */
object OverlayQuickAsk {

    /** Opens the panel, clearing any stale error. */
    fun expand(state: OverlayQuickAskState): OverlayQuickAskState =
        state.copy(isExpanded = true, error = null)

    /** Hides the panel and forgets the draft (used by the close affordance). */
    fun collapse(): OverlayQuickAskState = OverlayQuickAskState()

    /** Records typing; an error clears as soon as the user edits the text. */
    fun onInput(state: OverlayQuickAskState, value: String): OverlayQuickAskState =
        state.copy(input = value, error = null)

    /** «Отправить» is only available for a non-blank draft when nothing is in flight. */
    fun canSubmit(state: OverlayQuickAskState): Boolean =
        state.isExpanded && !state.isSending && state.input.isNotBlank()

    /** The text to send, or null when there is nothing meaningful to send. */
    fun payload(state: OverlayQuickAskState): String? = state.input.trim().ifBlank { null }

    /** Marks the submit as in flight. */
    fun sending(state: OverlayQuickAskState): OverlayQuickAskState =
        state.copy(isSending = true, error = null)

    /**
     * The hand-off failed: stop the spinner but keep [OverlayQuickAskState.input] so the user can
     * retry without retyping.
     */
    fun failed(state: OverlayQuickAskState, message: String): OverlayQuickAskState =
        state.copy(isSending = false, error = message)

    /** The hand-off succeeded: the panel closes and the draft is dropped. */
    fun sent(): OverlayQuickAskState = OverlayQuickAskState()
}
