package com.andharness.ru.ui.theme

/**
 * Which color scheme the app renders with.
 *
 * [DARK] is the default (design decision D3, `DESIGN/about.md`): the brand palette is dark-first
 * and the live toggle flips between [DARK] and [LIGHT]. [SYSTEM] is kept as an option (follows the
 * OS setting) even though the design default does not use it.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** Whether this mode resolves to the dark scheme given the current OS setting. */
    fun isDark(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }
}
