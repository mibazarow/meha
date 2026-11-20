package com.andharness.ru.domain.ui


/**
 * Pure, Android-free model of one accessibility node, plus the semantic [UiSelector] that resolves
 * it. The `ui_*` tools convert the live `AccessibilityNodeInfo` hierarchy into this shape
 * (`AccessibilityTree.toUiNode`) and then resolve targets by meaning instead of raw coordinates, so
 * a recorded step keeps working after an app update moves a control around.
 *
 * Field semantics follow the platform `AccessibilityNodeInfo`:
 * - `text` / `contentDescription` / `className` / `isClickable` / `getBoundsInScreen`:
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 * - `viewIdResourceName` ("the fully qualified resource name ... [package]:id/[name]"); this model
 *   keeps only the short name after `/`:
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getViewIdResourceName()
 * - `getBoundsInScreen(Rect)` — "the node's bounds in screen coordinates":
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getBoundsInScreen(android.graphics.Rect)
 */
data class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    /** Short view id, i.e. `viewIdResourceName.substringAfterLast('/')`; `null` when absent. */
    val resourceId: String? = null,
    /** Fully qualified class name, e.g. `android.widget.Button`. */
    val className: String? = null,
    val bounds: UiBounds = UiBounds.EMPTY,
    /** `AccessibilityNodeInfo.isClickable`. */
    val clickable: Boolean = false,
    /** `AccessibilityNodeInfo.isEditable` (kept for reporting; not used by the resolver). */
    val editable: Boolean = false,
    /** `AccessibilityNodeInfo.isScrollable` (kept for reporting; not used by the resolver). */
    val scrollable: Boolean = false,
    val children: List<UiNode> = emptyList(),
    /**
     * Opaque platform handle (an `AccessibilityNodeInfo` in production) carried through resolution so
     * the data layer can still call `performAction` on the resolved node. Kept as `Any?` so this file
     * stays free of Android imports; `null` in tests.
     */
    val ref: Any? = null,
)
