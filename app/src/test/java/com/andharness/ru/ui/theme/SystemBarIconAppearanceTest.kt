package com.andharness.ru.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests pinning the theme→system-bar-icon mapping used by `MainActivity`: light theme must
 * render dark status/navigation icons (light-bar appearance = true), dark theme must render light
 * icons (default appearance = false). See [isLightSystemBarAppearance].
 */
class SystemBarIconAppearanceTest {

    @Test
    fun `a light theme requests the light bar appearance for dark icons`() {
        assertTrue(isLightSystemBarAppearance(darkTheme = false))
    }

    @Test
    fun `a dark theme requests the default appearance for light icons`() {
        assertFalse(isLightSystemBarAppearance(darkTheme = true))
    }

    @Test
    fun `the mapping follows the resolved ThemeMode`() {
        assertTrue(isLightSystemBarAppearance(ThemeMode.LIGHT.isDark(systemInDark = false)))
        assertTrue(isLightSystemBarAppearance(ThemeMode.SYSTEM.isDark(systemInDark = false)))
        assertFalse(isLightSystemBarAppearance(ThemeMode.SYSTEM.isDark(systemInDark = true)))
        assertFalse(isLightSystemBarAppearance(ThemeMode.DARK.isDark(systemInDark = false)))
    }
}
