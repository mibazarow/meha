package com.andharness.ru.data.tools.ui


/**
 * One node of the on-screen accessibility tree worth reporting: it has visible text, a content
 * description, a view id, or is interactive (clickable / editable). Coordinates are the node's
 * bounds in screen pixels, so the same snapshot can drive both understanding and tapping.
 *
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 */
data class ScreenElement(
    val depth: Int,
    val text: String?,
    val description: String?,
    val resourceId: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** Display label: visible text, else content description, else the class name fallback. */
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: description?.takeIf { it.isNotBlank() }
            ?: className
            ?: "?"
}
