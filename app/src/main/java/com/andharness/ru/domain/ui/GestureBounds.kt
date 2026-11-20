package com.andharness.ru.domain.ui

import kotlin.math.roundToInt

/**
 * Pure, Android-free geometry for the `ui_gesture_multi` tool (two symmetric pinch/zoom strokes).
 *
 * `GestureDescription.StrokeDescription` requires the stroke path to have exactly one contour whose
 * bounds are NOT negative: any negative coordinate throws
 * `IllegalArgumentException("Path bounds must not be negative")` and the whole gesture is lost
 * (bug BUG-024, reproduced with `cx=360, end_distance=800`). Path coordinates are screen pixels, so
 * every computed point must stay inside `0..width-1` x `0..height-1`. Keeping this clamp in a plain
 * object (no `android.graphics.Path`, no `DisplayMetrics`) lets it be unit-tested on the JVM.
 *
 * Docs:
 * - `GestureDescription.StrokeDescription` — "The bounds of the path must not be negative":
 *   https://developer.android.com/reference/android/accessibilityservice/GestureDescription.StrokeDescription
 * - AOSP check `bounds.bottom >= 0 && bounds.top >= 0 && bounds.right >= 0 && bounds.left >= 0`:
 *   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/GestureDescription.java
 * - Display size comes from `Resources.getDisplayMetrics()` (`DisplayMetrics.widthPixels` /
 *   `heightPixels`), the same source the other UI tools use:
 *   https://developer.android.com/reference/android/content/res/Resources#getDisplayMetrics()
 *   https://developer.android.com/reference/android/util/DisplayMetrics#widthPixels
 */
object GestureBounds {

    /** One clamped pointer's travel along the X axis at a fixed Y. */
    data class Stroke(val startX: Int, val endX: Int, val y: Int)

    /** Result of clamping one multitouch request; every coordinate is inside the screen. */
    data class Clamped(
        val left: Stroke,
        val right: Stroke,
        /** Realized distance between the two pointers at the start of the gesture, in px. */
        val startSpan: Int,
        /** Realized distance between the two pointers at the end of the gesture, in px. */
        val endSpan: Int,
        /** True when nothing had to be clipped; false when the request was reduced to fit. */
        val exact: Boolean,
    )

    /**
     * Rounds [value] to the nearest pixel and clamps it into `0..size-1`. A non-positive [size]
     * (unknown display) yields 0, so the result can never be negative — the exact condition the
     * platform enforces.
     */
    fun clampCoordinate(value: Float, size: Int): Int {
        if (size <= 0) return 0
        if (value.isNaN()) return 0
        return value.roundToInt().coerceIn(0, size - 1)
    }

    /**
     * Clamps a symmetric span around [cx] to `0..width-1` and returns `(leftX, rightX)`.
     *
     * This is the BUG-024 shape: for `cx = 360`, `span = 800` the left point would be `-40`; here it
     * collapses to `0` (and a far right point to `width-1`), so the resulting path bounds are never
     * negative. A negative or NaN span is treated as 0 (the caller validates it anyway).
     */
    fun clampSpan(cx: Float, span: Float, width: Int): Pair<Int, Int> {
        val half = normalizeSpan(span) / 2f
        return clampCoordinate(cx - half, width) to clampCoordinate(cx + half, width)
    }

    /**
     * Full clamp of one `ui_gesture_multi` request around the centre `(cx, cy)`.
     *
     * Returns null only when the display size is unusable (`width <= 0 || height <= 0`). Otherwise
     * every returned coordinate is within `0..width-1` / `0..height-1`; [Clamped.exact] tells
     * whether the request fitted untouched or had to be reduced. A degenerate result (both spans
     * zero, i.e. no pointer moves because the centre is off-screen or the distances are zero) must
     * be rejected by the caller with an honest error instead of being dispatched.
     */
    fun clamp(
        cx: Float,
        cy: Float,
        startDistance: Float,
        endDistance: Float,
        width: Int,
        height: Int,
    ): Clamped? {
        if (width <= 0 || height <= 0) return null
        val start = normalizeSpan(startDistance)
        val end = normalizeSpan(endDistance)
        val (leftStart, rightStart) = clampSpan(cx, start, width)
        val (leftEnd, rightEnd) = clampSpan(cx, end, width)
        val y = clampCoordinate(cy, height)
        val exact = startDistance == start && endDistance == end &&
            isInside(cx - start / 2f, width) &&
            isInside(cx + start / 2f, width) &&
            isInside(cx - end / 2f, width) &&
            isInside(cx + end / 2f, width) &&
            isInside(cy, height)
        return Clamped(
            left = Stroke(leftStart, leftEnd, y),
            right = Stroke(rightStart, rightEnd, y),
            startSpan = rightStart - leftStart,
            endSpan = rightEnd - leftEnd,
            exact = exact,
        )
    }

    private fun normalizeSpan(span: Float): Float = if (span.isNaN() || span < 0f) 0f else span

    private fun isInside(x: Float, size: Int): Boolean = !x.isNaN() && x >= 0f && x <= (size - 1).toFloat()
}
