package com.andharness.ru.ui.theme

/**
 * Resolves the value for `WindowInsetsControllerCompat.isAppearanceLightStatusBars` /
 * `isAppearanceLightNavigationBars` from the app's RESOLVED theme (see [Theme]).
 *
 * The two properties select a **light** system-bar appearance, i.e. a light bar that renders
 * **dark** icons, so they are visible. The platform constant documents exactly that: it "changes
 * the foreground color for light status bars so that the items on the bar can be read clearly"
 * (https://developer.android.com/reference/android/view/WindowInsetsController#APPEARANCE_LIGHT_STATUS_BARS).
 * The AndroidX wrapper exposes the same semantics (param `isLight`):
 * https://developer.android.com/reference/androidx/core/view/WindowInsetsControllerCompat#setAppearanceLightStatusBars(boolean)
 *
 * Consequence for this app: a LIGHT theme must request the light-bar appearance (`true` → dark
 * icons, legible on the light surface) and a DARK theme must request the default appearance
 * (`false` → light icons, legible on the dark surface). Hence the inversion.
 *
 * Pure and Android-free on purpose, so the mapping is covered by a JVM unit test rather than
 * relying on device-only verification.
 */
fun isLightSystemBarAppearance(darkTheme: Boolean): Boolean = !darkTheme
