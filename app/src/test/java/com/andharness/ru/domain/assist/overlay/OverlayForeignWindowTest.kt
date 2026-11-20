package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the window-selection rule behind the overlay's screen-context capture (BUG-038).
 *
 * The rule must pick the real foreground app and must never report the overlay's own windows, an
 * unreadable window, the IME or a system window as "what is on screen".
 */
class OverlayForeignWindowTest {

    private fun app(
        pkg: String?,
        active: Boolean = false,
        focused: Boolean = false,
        layer: Int = 0,
        hasRoot: Boolean = true,
    ) = OverlayWindowCandidate(
        type = OverlayForeignWindow.TYPE_APPLICATION,
        packageName = pkg,
        hasRoot = hasRoot,
        active = active,
        focused = focused,
        layer = layer,
    )

    @Test
    fun picksTheForeignApplicationWindow() {
        val windows = listOf(
            app("com.andharness.ru", active = true, layer = 10), // our own focused panel — topmost
            app("com.android.settings", layer = 5),
        )
        assertEquals(1, OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))
    }

    @Test
    fun ignoresOwnOverlayEvenWhenActiveAndOnTop() {
        val windows = listOf(
            app("com.andharness.ru", active = true, focused = true, layer = 99),
        )
        assertNull(OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))
    }

    @Test
    fun prefersTheActiveForeignWindowOverAFocusedOrHigherLayerOne() {
        val windows = listOf(
            app("com.example.higher", focused = true, layer = 50),
            app("com.android.settings", active = true, layer = 5),
        )
        assertEquals(1, OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))
    }

    @Test
    fun prefersFocusedThenTopmostLayer() {
        val windows = listOf(
            app("com.example.top", layer = 80),
            app("com.example.focused", focused = true, layer = 10),
        )
        assertEquals(1, OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))

        val noFocus = listOf(
            app("com.example.low", layer = 10),
            app("com.example.high", layer = 20),
        )
        assertEquals(1, OverlayForeignWindow.pickIndex(noFocus, "com.andharness.ru"))
    }

    @Test
    fun ignoresInputMethodSystemAndAccessibilityOverlayWindows() {
        val windows = listOf(
            OverlayWindowCandidate(OverlayForeignWindow.TYPE_INPUT_METHOD, "com.google.android.inputmethod", true, true, true, 90),
            OverlayWindowCandidate(OverlayForeignWindow.TYPE_SYSTEM, "com.android.systemui", true, false, false, 95),
            OverlayWindowCandidate(OverlayForeignWindow.TYPE_ACCESSIBILITY_OVERLAY, "com.andharness.ru", true, false, false, 96),
            app("com.android.settings", layer = 5),
        )
        assertEquals(3, OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))
    }

    @Test
    fun ignoresWindowsWithoutARootOrWithABlankPackage() {
        val windows = listOf(
            app("com.example.unreadable", hasRoot = false, layer = 60),
            app(null, hasRoot = true, layer = 50),
            app("   ", hasRoot = true, layer = 40),
            app("com.android.settings", layer = 5),
        )
        assertEquals(3, OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))
    }

    @Test
    fun returnsNullWhenOnlyOurOwnWindowsAreReadable() {
        val windows = listOf(
            app("com.andharness.ru", hasRoot = true, layer = 70),
            OverlayWindowCandidate(OverlayForeignWindow.TYPE_SYSTEM, "com.android.systemui", true, false, false, 60),
        )
        assertNull(OverlayForeignWindow.pickIndex(windows, "com.andharness.ru"))
    }

    @Test
    fun isForeignIsHonestAboutUnknownPackages() {
        assertTrue(OverlayForeignWindow.isForeign("com.android.settings", "com.andharness.ru"))
        assertFalse(OverlayForeignWindow.isForeign("com.andharness.ru", "com.andharness.ru"))
        assertFalse(OverlayForeignWindow.isForeign(null, "com.andharness.ru"))
        assertFalse(OverlayForeignWindow.isForeign("", "com.andharness.ru"))
        assertFalse(OverlayForeignWindow.isForeign("   ", "com.andharness.ru"))
    }
}
