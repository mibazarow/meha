package com.andharness.ru.service.overlay

import android.content.Context
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andharness.ru.domain.assist.overlay.OverlayQuickAskState
import com.andharness.ru.service.OverlayComposeHost
import com.andharness.ru.ui.overlay.OverlayQuickAskPanel
import com.andharness.ru.ui.theme.Theme
import com.andharness.ru.ui.theme.ThemeStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the focusable quick-ask panel window: its [OverlayComposeHost], [WindowManager.LayoutParams]
 * and the `addView`/`removeView` calls, plus the panel's Compose content.
 *
 * Hiding removes the view but — crucially — does not destroy the [OverlayComposeHost], its
 * `LifecycleOwner` or the composition. [WindowManager.removeView] only *schedules* ViewRootImpl's
 * final traversal; if the view-tree owners were cleared in the same synchronous block that pending
 * traversal would still measure the `ComposeView`, Compose would re-create the composition and throw
 * `IllegalStateException: ViewTreeLifecycleOwner not found` (BUG-029). The host is disposed exactly
 * once, in [destroy], after a synchronous removal.
 */
internal class OverlayPanelController(
    private val context: Context,
    private val themeStore: ThemeStore,
    private val windows: OverlayWindowHost,
    private val quickAsk: StateFlow<OverlayQuickAskState>,
    private val onInputChange: (String) -> Unit,
    private val onSubmit: () -> Unit,
    private val onClose: () -> Unit,
) {

    private var host: OverlayComposeHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false
    private var windowManager: WindowManager? = null

    val isAdded: Boolean get() = added

    fun open() {
        if (added) return
        val h = host ?: createHost().also { host = it }
        val overlay = windows.resolve()
        // A fresh LayoutParams per add: the platform sets `token` on it during addView, and a token
        // from a previous accessibility-service connection would be stale after a reconnect.
        val p = windows.panelParams(overlay.second).also { params = it }
        windowManager = overlay.first
        h.attach()
        try {
            overlay.first.addView(h.view, p)
            added = true
        } catch (e: Exception) {
            // The host is kept (owners intact) so a later open() can retry; destroy() disposes it.
            Log.e(TAG, "addView(panel) failed", e)
            added = false
            windowManager = null
        }
    }

    /**
     * Removes the panel view. [immediate] detaches synchronously and is for teardown only — the
     * pending-traversal reasoning above is exactly why normal hiding must use `removeView` and keep
     * the host alive.
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
                // `removeView` may already have queued the detach (e.g. closeAssistant → hidePanel →
                // stopSelf → onDestroy); removing immediately is still correct for teardown, but only
                // while the view is actually in a window.
                if (h.view.isAttachedToWindow) wm.removeViewImmediate(h.view)
            } else {
                wm.removeView(h.view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "removeView(panel) failed: ${e.message}")
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

    private fun createHost(): OverlayComposeHost = OverlayComposeHost(context) {
        val themeMode by themeStore.mode.collectAsState()
        val state by quickAsk.collectAsState()
        Theme(themeMode = themeMode) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                OverlayQuickAskPanel(
                    state = state,
                    onInputChange = onInputChange,
                    onSubmit = onSubmit,
                    onClose = onClose,
                )
            }
        }
    }

    private companion object {
        const val TAG = "OverlayAssistant"
    }
}
