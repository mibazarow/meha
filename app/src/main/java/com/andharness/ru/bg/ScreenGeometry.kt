package com.andharness.ru.bg

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics

/**
 * Real display geometry for the screen tools: the default display's real metrics and the
 * even-dimension clamp H.264 encoders require.
 *
 * References:
 * - [Display.getRealMetrics] (the only API available below API 30 from a Service context without a
 *   Window) — https://developer.android.com/reference/android/view/Display#getRealMetrics(android.util.DisplayMetrics)
 */
internal object ScreenGeometry {

    /** Encoders on every device accept at most this on the longest side. */
    private const val MAX_DIMENSION = 1920

    /**
     * Real display metrics of the default display. `Display.getRealMetrics` is deprecated but is the
     * only API available below API 30 from a Service context without a Window.
     */
    @Suppress("DEPRECATION")
    fun screenMetrics(context: Context): DisplayMetrics {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = DisplayMetrics()
        val display = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return metrics
    }

    /**
     * H.264 requires even dimensions; the longest side is capped at [MAX_DIMENSION]. The projection
     * is a fixed size for the whole session — rotating the device mid-recording does not resize it.
     */
    fun fitEven(width: Int, height: Int): Pair<Int, Int> {
        var w = width
        var h = height
        val longest = maxOf(w, h)
        if (longest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toDouble() / longest
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        if (w % 2 != 0) w -= 1
        if (h % 2 != 0) h -= 1
        return maxOf(w, 2) to maxOf(h, 2)
    }
}
