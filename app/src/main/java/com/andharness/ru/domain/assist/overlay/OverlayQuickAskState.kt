package com.andharness.ru.domain.assist.overlay

/**
 * State of the in-overlay quick-ask panel (text field + «Отправить»).
 *
 * [isExpanded] `false` means the panel is hidden and only the bubble is on screen; [isSending]
 * guards against a double submit while the chat is being created, [error] carries the honest
 * failure text when the hand-off to the app did not work. (Dictation was removed in redesign §2
 * phase 5.)
 */
data class OverlayQuickAskState(
    val input: String = "",
    val isExpanded: Boolean = false,
    val isSending: Boolean = false,
    val error: String? = null,
)
