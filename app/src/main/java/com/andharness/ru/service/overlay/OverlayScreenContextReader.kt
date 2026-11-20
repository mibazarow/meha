package com.andharness.ru.service.overlay

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.domain.assist.overlay.OverlayForeignWindow
import com.andharness.ru.domain.assist.overlay.OverlayScreenContext
import com.andharness.ru.domain.assist.overlay.OverlayWindowCandidate
import com.andharness.ru.domain.assist.overlay.ScreenContext
import com.andharness.ru.data.tools.ui.AccessibilityTree
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a best-effort, bounded snapshot of the screen the user is asking about, and composes the
 * seeded message for the agent from it.
 *
 * Node access runs on the main thread because `AccessibilityNodeInfo` access is documented to
 * require a Looper thread; the traversal itself is capped at [CONTEXT_MAX_NODES] so it can never
 * stall a frame. Everything is wrapped in a try/catch: a missing or half-dead accessibility service
 * must degrade to "no context", never to a crash and never to invented content.
 *
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 * https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRoot()
 */
internal class OverlayScreenContextReader(
    private val ownPackage: String,
) {

    /**
     * Returns null only when the accessibility service is OFF (the honest "unavailable" path).
     */
    fun capture(): ScreenContext? {
        val service = AccessibilityAutomationService.instance ?: return null
        return try {
            val root = activeForeignRoot(service) ?: return ScreenContext()
            val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() }
            // Only trust the cached window class when it belongs to the same app we just captured.
            val foreground = AccessibilityAutomationService.lastForegroundWindow()
            val activity = foreground?.className?.takeIf { pkg != null && foreground.packageName == pkg }
            val labels = AccessibilityTree
                .collect(root, maxNodes = CONTEXT_MAX_NODES)
                .map { it.label }
                .filter { it.isNotBlank() && it != "?" }
            ScreenContext(packageName = pkg, activityName = activity, labels = labels)
        } catch (e: Exception) {
            Log.w(TAG, "screen context capture failed: ${e.message}")
            ScreenContext()
        }
    }

    /**
     * Reads the current screen into the compact context block (or one of the honest "unavailable"
     * notes). The accessibility read stays on Main (bounded by [CONTEXT_MAX_NODES]); the pure string
     * formatting is moved off the main thread.
     *
     * Only the SCREEN-REQUEST branch of the overlay hand-off calls this, so an unrelated request
     * never touches the screen. The live read is preferred, but when it comes back empty while the
     * panel is focused (our own window owns `getRootInActiveWindow`, and a non-active window's root
     * may be unreadable on Android 16) an optional [contextAtOpen] snapshot taken earlier, while the
     * foreign app was still active, is used instead. Both branches stay honest: `null` means the
     * accessibility service is off, and an all-empty result keeps the "unreadable" note.
     */
    suspend fun readBlock(contextAtOpen: ScreenContext?): String {
        val live = capture()
        val context = when {
            live == null -> null
            !live.isEmpty -> live
            else -> contextAtOpen?.takeIf { !it.isEmpty } ?: live
        }
        return withContext(Dispatchers.Default) {
            when {
                context == null -> OverlayScreenContext.UNAVAILABLE_ACCESSIBILITY
                else -> OverlayScreenContext.format(context) ?: OverlayScreenContext.UNAVAILABLE_UNREADABLE
            }
        }
    }

    /**
     * The root of the app that is actually on screen, but never our own overlay.
     *
     * `getRootInActiveWindow()` is used first because it is exact when it is foreign. Once our
     * focusable quick-ask panel is up it becomes the active window, so the app underneath has to be
     * found by enumerating the interactive windows; the choice itself is the pure, unit-tested
     * [OverlayForeignWindow.pickIndex] rule (active first, then focused, then topmost, among readable
     * foreign application windows).
     *
     * The package check is what keeps the honesty rule: an app overlay is reported by
     * `AccessibilityWindowInfo` as `TYPE_APPLICATION`, so filtering by type alone would let our own
     * bubble/panel be reported as "the current screen". A window whose root cannot be read, or whose
     * package is blank, is never attributed to the foreground app.
     */
    private fun activeForeignRoot(service: AccessibilityAutomationService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow
        if (root != null && OverlayForeignWindow.isForeign(root.packageName?.toString(), ownPackage)) {
            return root
        }
        val windows = try {
            service.windows
        } catch (e: Exception) {
            Log.w(TAG, "windows() failed: ${e.message}")
            null
        } ?: return null
        val roots = windows.map { window -> try { window.root } catch (e: Exception) { null } }
        val candidates = windows.indices.map { i ->
            OverlayWindowCandidate(
                type = windows[i].type,
                packageName = roots[i]?.packageName?.toString(),
                hasRoot = roots[i] != null,
                active = windows[i].isActive,
                focused = windows[i].isFocused,
                layer = windows[i].layer,
            )
        }
        val index = OverlayForeignWindow.pickIndex(candidates, ownPackage) ?: return null
        return roots[index]
    }

    private companion object {
        const val TAG = "OverlayAssistant"

        /**
         * Upper bound on accessibility nodes read for the current-screen context. Small on purpose:
         * the capture runs on the main thread and must stay cheap.
         */
        const val CONTEXT_MAX_NODES = 40
    }
}
