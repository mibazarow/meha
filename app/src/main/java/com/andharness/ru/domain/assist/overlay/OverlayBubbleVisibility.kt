package com.andharness.ru.domain.assist.overlay

/**
 * Android-free rules for keeping the floating bubble attached.
 *
 * The bubble lives in a `WindowManager`-added overlay window. That window's `Surface` can be hidden
 * by the platform (e.g. an app in the foreground that sets
 * `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` hides every non-system overlay), and the window can
 * also disappear entirely when the accessibility-service token it was added with goes away. The
 * service therefore re-checks "is the bubble view still attached?" whenever it closes the panel or
 * the foreground changes, and re-adds the window when it is not. These two predicates are the whole
 * decision, so they live here and are unit-tested without a device.
 *
 * References:
 * - `WindowManager.addView` / `View.isAttachedToWindow` —
 *   https://developer.android.com/reference/android/view/WindowManager#addView(android.view.View,%20android.view.ViewGroup.LayoutParams)
 *   https://developer.android.com/reference/android/view/View#isAttachedToWindow()
 * - `WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (why a non-system
 *   overlay can be force-hidden by the foreground app) —
 *   https://developer.android.com/reference/android/view/WindowManager.LayoutParams#SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
 */
object OverlayBubbleVisibility {

    /** The bubble should be added: the service intends to show it but it is not added yet. */
    fun shouldAdd(bubbleAdded: Boolean): Boolean = !bubbleAdded

    /**
     * The bubble is marked as added but its view is no longer attached to a window (the platform or
     * the accessibility-service token dropped it), so it must be re-added instead of assumed
     * visible. When [bubbleAdded] is false the normal [shouldAdd] path applies.
     */
    fun shouldReattach(bubbleAdded: Boolean, viewAttached: Boolean): Boolean =
        bubbleAdded && !viewAttached
}
