package com.andharness.ru.domain.assist.overlay

/**
 * The compact, Android-free snapshot of what is currently on screen, captured when the user submits
 * a question from the floating overlay.
 *
 * It is deliberately tiny — the foreground package, an optional activity/class name and a bounded
 * list of visible labels — because it is prepended to a chat message, not to a tool call. The
 * capture itself (reading `AccessibilityService.rootInActiveWindow`) lives in
 * `OverlayAssistantService`; this type only models the result so the formatting is unit-testable.
 *
 * [packageName] / [activityName] are nullable and [labels] may be empty: a screen the accessibility
 * layer cannot read must never be invented.
 */
data class ScreenContext(
    val packageName: String? = null,
    val activityName: String? = null,
    val labels: List<String> = emptyList(),
) {
    /** True when literally nothing usable was captured — the context is then omitted entirely. */
    val isEmpty: Boolean
        get() = packageName.isNullOrBlank() && activityName.isNullOrBlank() &&
            labels.none { it.isNotBlank() }
}
