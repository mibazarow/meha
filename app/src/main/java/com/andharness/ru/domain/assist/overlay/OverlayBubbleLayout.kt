package com.andharness.ru.domain.assist.overlay

/**
 * Android-free geometry for the draggable overlay bubble.
 *
 * The bubble lives in its own `TYPE_APPLICATION_OVERLAY` window and is moved by writing
 * [android.view.WindowManager.LayoutParams.x] / `.y`, so a careless drag could push it off-screen
 * where it becomes unreachable (and the user would have no way to close it). Keeping the clamp pure
 * makes that invariant testable without a device.
 *
 * References:
 * - `WindowManager.LayoutParams` (`TYPE_APPLICATION_OVERLAY`, `x`, `y`, `gravity`) —
 *   https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
 * - `WindowManager.getCurrentWindowMetrics()` (API 30+, used by the service for the real bounds) —
 *   https://developer.android.com/reference/android/view/WindowManager#getCurrentWindowMetrics()
 */
object OverlayBubbleLayout {

    /**
     * Clamps a proposed top-left corner so the whole [bubbleWidth] x [bubbleHeight] bubble stays
     * inside `0..screenWidth-1` x `0..screenHeight-1`. Unknown (non-positive) screen bounds leave
     * the position at its non-negative value; an unknown bubble size is treated as 0.
     */
    fun clamp(
        x: Int,
        y: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): BubblePosition {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return BubblePosition(x.coerceAtLeast(0), y.coerceAtLeast(0))
        }
        val width = bubbleWidth.coerceAtLeast(0)
        val height = bubbleHeight.coerceAtLeast(0)
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        return BubblePosition(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /**
     * Snaps the bubble to the nearest horizontal edge (keeping [position]'s y), the usual behaviour
     * for a floating bubble after a drag ends. Keeps the same in-bounds invariant as [clamp].
     */
    fun snapToEdge(
        position: BubblePosition,
        bubbleWidth: Int,
        screenWidth: Int,
    ): BubblePosition {
        if (screenWidth <= 0) return BubblePosition(position.x.coerceAtLeast(0), position.y)
        val width = bubbleWidth.coerceAtLeast(0)
        val right = (screenWidth - width).coerceAtLeast(0)
        val centre = position.x + width / 2
        return position.copy(x = if (centre <= screenWidth / 2) 0 else right)
    }
}
