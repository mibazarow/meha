package com.andharness.ru.service.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.andharness.ru.domain.assist.overlay.OverlayBubbleLayout
import com.andharness.ru.domain.assist.overlay.OverlayBubbleVisibility
import com.andharness.ru.service.OverlayComposeHost
import com.andharness.ru.ui.overlay.OverlayBubble
import com.andharness.ru.ui.theme.Theme
import com.andharness.ru.ui.theme.ThemeStore

/**
 * Owns the floating bubble window: its [OverlayComposeHost], [WindowManager.LayoutParams] and the
 * `addView`/`updateViewLayout`/`removeView` calls.
 *
 * The bubble is idempotent across the repeated starts the service legitimately gets (the settings
 * toggle, the boot receiver and the startup reconciliation): an already-attached bubble window is
 * left alone, while a window the platform dropped is re-added (BUG-037 resilience).
 *
 * The [WindowManager] the bubble was added with is remembered next to [added]: normally the
 * accessibility service's one, which the platform does not force-hide over apps that set
 * `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (e.g. Settings); the service's own manager is the
 * fallback when accessibility is off. `updateViewLayout`/`removeView` must go through the same
 * instance.
 */
internal class OverlayBubbleController(
    private val context: Context,
    private val themeStore: ThemeStore,
    private val windows: OverlayWindowHost,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onAddFailed: () -> Unit,
) {

    private var host: OverlayComposeHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false
    private var windowManager: WindowManager? = null

    val isAdded: Boolean get() = added

    fun show() {
        val h = host ?: createHost().also { host = it }
        val attached = h.view.isAttachedToWindow
        if (added && attached) return
        if (OverlayBubbleVisibility.shouldReattach(added, attached)) {
            Log.i(TAG, "bubble view detached — re-adding the window")
            reset()
        }
        val previous = params
        val overlay = windows.resolve()
        val p = windows.bubbleParams(overlay.second, previous).also { params = it }
        windowManager = overlay.first
        h.attach()
        try {
            overlay.first.addView(h.view, p)
            added = true
        } catch (e: Exception) {
            // e.g. BadTokenException when the grant was revoked between the check and addView.
            // Do NOT destroy the host here: `destroy()` clears the view-tree owners, and doing that
            // in the same block that just failed to add the view would leave a host that can never be
            // reused. Teardown happens once in destroy().
            Log.e(TAG, "addView(bubble) failed", e)
            reset()
            onAddFailed()
        }
    }

    fun move(dx: Float, dy: Float) {
        val h = host ?: return
        val p = params ?: return
        val wm = windowManager ?: windows.fallbackWindowManager()
        val screen = windows.screenBounds()
        val position = OverlayBubbleLayout.clamp(
            x = (p.x + dx).toInt(),
            y = (p.y + dy).toInt(),
            bubbleWidth = h.view.width,
            bubbleHeight = h.view.height,
            screenWidth = screen.first,
            screenHeight = screen.second,
        )
        p.x = position.x
        p.y = position.y
        try {
            wm.updateViewLayout(h.view, p)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout(bubble) failed: ${e.message}")
        }
    }

    /**
     * Re-adds the bubble if it is supposed to be shown but its view is no longer attached (the
     * platform or the accessibility overlay token dropped the window). Cheap when the bubble is
     * still attached. Called on panel close and on configuration changes so the bubble cannot stay
     * missing after another window lifecycle event.
     */
    fun reassert() {
        val h = host ?: return
        if (OverlayBubbleVisibility.shouldReattach(added, h.view.isAttachedToWindow)) {
            Log.i(TAG, "re-asserting the bubble window")
            show()
        }
    }

    /**
     * Removes the bubble view. [immediate] is used only on teardown: [WindowManager.removeView] just
     * schedules the final traversal, so the owners must survive it; [WindowManager.removeViewImmediate]
     * detaches synchronously, which is what lets `onDestroy` safely dispose the host afterwards.
     * https://developer.android.com/reference/android/view/WindowManager#removeViewImmediate(android.view.View)
     */
    fun remove(immediate: Boolean = false) {
        val h = host ?: return
        if (!added && !immediate) return
        val wm = windowManager ?: windows.fallbackWindowManager()
        added = false
        windowManager = null
        try {
            if (immediate) {
                // `removeView` may already have queued the detach; removing immediately is still the
                // right thing for teardown, but only while the view actually is in a window.
                if (h.view.isAttachedToWindow) wm.removeViewImmediate(h.view)
            } else {
                wm.removeView(h.view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeView(bubble) failed: ${e.message}")
        }
    }

    /** Disposes the host and drops the window state. The view must have been removed first. */
    fun destroy() {
        host?.destroy()
        host = null
        params = null
        added = false
        windowManager = null
    }

    private fun reset() {
        added = false
        windowManager = null
    }

    private fun createHost(): OverlayComposeHost = OverlayComposeHost(context) {
        val themeMode by themeStore.mode.collectAsState()
        Theme(themeMode = themeMode) {
            OverlayBubble(
                onTap = onTap,
                onLongPress = onLongPress,
                onDrag = { dx, dy -> move(dx, dy) },
            )
        }
    }

    private companion object {
        const val TAG = "OverlayAssistant"
    }
}
