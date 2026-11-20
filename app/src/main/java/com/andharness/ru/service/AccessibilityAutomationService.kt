package com.andharness.ru.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.andharness.ru.data.automation.AutomationEventBus
import com.andharness.ru.data.tools.ui.UiEventRecorder

/**
 * Accessibility service that exposes the screen to the `ui_*` tools: read the node tree and perform
 * taps, swipes and text input in any app. The user must enable it manually in Settings > Accessibility.
 *
 * It also feeds:
 * - the `app_opened` automation trigger: `TYPE_WINDOW_STATE_CHANGED` events carry the package of the
 *   now-foreground window and are forwarded (non-blocking) to [AutomationEventBus];
 * - `ui_trigger_subscribe`: the same events plus `TYPE_WINDOWS_CHANGED` are recorded in the bounded
 *   [UiEventRecorder] while the agent has that recording switched on.
 *
 * Reference: https://developer.android.com/guide/topics/ui/accessibility/service
 */
class AccessibilityAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                // Remember the foreground window/activity class for the overlay's context capture.
                // Our own windows (the floating assistant) are skipped so the overlay can never
                // report itself as "what is on screen".
                if (pkg != packageName) {
                    lastForeground = ForegroundWindow(pkg, e.className?.toString())
                }
                UiEventRecorder.record(pkg, e.className?.toString(), e.eventType, e.eventTime)
                AutomationEventBus.windowStateChanged(pkg)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = e.packageName?.toString() ?: return
                UiEventRecorder.record(pkg, e.className?.toString(), e.eventType, e.eventTime)
            }
        }
    }

    override fun onInterrupt() = Unit

    /**
     * The `WindowManager` bound to this service's `TYPE_ACCESSIBILITY_OVERLAY` window token, or
     * `null` when the token is not available yet.
     *
     * `AccessibilityService.getSystemService(WINDOW_SERVICE)` returns a `WindowManagerImpl` whose
     * default token is the accessibility-overlay `WindowToken`. `WindowManagerService` only accepts
     * a `TYPE_ACCESSIBILITY_OVERLAY` window that is added with exactly that token
     * (`sanitizeWindowType`: `type == token.getWindowType()`), which is why the overlay must be added
     * through this instance — and why a normal `Context` `WindowManager` would fail here.
     *
     * Accessibility overlay windows are exempt from the platform's
     * `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` force-hiding that applies to
     * `TYPE_APPLICATION_OVERLAY` (see `WindowState#setForceHideNonSystemOverlayWindowIfNeeded`,
     * which only hides `isSystemAlertWindowType`/`TYPE_TOAST`) — this is what keeps the bubble
     * visible over apps such as Settings that hide non-system overlays.
     *
     * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getSystemService(java.lang.String)
     * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY
     */
    fun overlayWindowManager(): WindowManager? = runCatching {
        getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }.getOrNull()

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** The last non-self foreground window, as reported by `TYPE_WINDOW_STATE_CHANGED`. */
        data class ForegroundWindow(val packageName: String, val className: String?)

        @Volatile
        var instance: AccessibilityAutomationService? = null
            private set

        /** Last foreground window (package + class) outside our own app, or null before any event. */
        @Volatile
        private var lastForeground: ForegroundWindow? = null

        fun isEnabled(): Boolean = instance != null

        /**
         * Foreground package/class of the most recent window-state change in another app. The class
         * is usually the Activity (see `AccessibilityEvent#getClassName`), which is exactly what the
         * overlay's compact screen-context block reports:
         * https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#getClassName()
         */
        fun lastForegroundWindow(): ForegroundWindow? = lastForeground
    }
}
