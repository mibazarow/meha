package com.andharness.ru.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.andharness.ru.domain.assist.overlay.OverlayBubbleLayout
import com.andharness.ru.service.AccessibilityAutomationService

/**
 * Resolves the [WindowManager] + window type the overlay windows must use and builds the
 * [WindowManager.LayoutParams] for the bubble and the quick-ask panel.
 *
 * The accessibility service's manager is preferred: it adds `TYPE_ACCESSIBILITY_OVERLAY` windows,
 * which are **not** force-hidden by the platform when the foreground app sets
 * `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (Settings does exactly that), so the bubble stays
 * visible and tappable over other apps (BUG-037). A plain `TYPE_APPLICATION_OVERLAY` from the
 * service's own manager is the honest fallback when accessibility is not connected.
 *
 * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY
 * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
 */
internal class OverlayWindowHost(private val context: Context) {

    private val systemWindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** The service's own manager, used as the fallback both for adding and for removing views. */
    fun fallbackWindowManager(): WindowManager = systemWindowManager

    fun screenBounds(): Pair<Int, Int> {
        val bounds = systemWindowManager.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    fun resolve(): Pair<WindowManager, Int> {
        val accessible = AccessibilityAutomationService.instance?.overlayWindowManager()
        return if (accessible != null) {
            accessible to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            systemWindowManager to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    fun bubbleParams(
        type: Int,
        previous: WindowManager.LayoutParams? = null,
    ): WindowManager.LayoutParams {
        val screen = screenBounds()
        val diameterPx =
            (BUBBLE_SIZE_DP * context.resources.displayMetrics.density).toInt()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Not focusable: the bubble must never steal the keyboard or key events from the app
            // underneath it. NOT_TOUCH_MODAL keeps touches outside the bubble going to that app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyCutoutMode()
            // A re-added bubble stays where the user last dragged it.
            val initial = OverlayBubbleLayout.clamp(
                x = previous?.x ?: (screen.first - diameterPx - EDGE_MARGIN_PX),
                y = previous?.y ?: (screen.second / 3),
                bubbleWidth = diameterPx,
                bubbleHeight = diameterPx,
                screenWidth = screen.first,
                screenHeight = screen.second,
            )
            x = initial.x
            y = initial.y
        }
    }

    fun panelParams(type: Int): WindowManager.LayoutParams {
        val screen = screenBounds()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Focusable on purpose: the soft keyboard must attach to this window. Touches outside
            // it still reach the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            applyCutoutMode()
            y = (screen.second * PANEL_TOP_FRACTION).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    /**
     * A sanity cutout rule for the overlay windows. `LayoutParams.layoutInDisplayCutoutMode` only
     * exists on API 28+; on API 26/27 the field must not be touched (setting it would throw
     * `NoSuchFieldError`), so the write is version-guarded rather than relying on the field's
     * default.
     * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#layoutInDisplayCutoutMode
     */
    private fun WindowManager.LayoutParams.applyCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private companion object {
        const val BUBBLE_SIZE_DP = 56
        const val EDGE_MARGIN_PX = 24
        const val PANEL_TOP_FRACTION = 0.12f
    }
}
