package com.andharness.ru.domain.assist.overlay

/**
 * One accessibility window as seen by `AccessibilityService.getWindows()`, reduced to the fields the
 * overlay's screen-context capture needs. Deliberately Android-free so the *selection rule* is
 * unit-testable without a device.
 *
 * `type` uses the same integer values as `android.view.accessibility.AccessibilityWindowInfo`:
 * `TYPE_APPLICATION = 1`, `TYPE_INPUT_METHOD = 2`, `TYPE_SYSTEM = 3`,
 * `TYPE_ACCESSIBILITY_OVERLAY = 4`.
 *
 * `packageName` is the package of the window's root node (the only place a package is exposed), so
 * `hasRoot == false` also means the package is unknown.
 */
data class OverlayWindowCandidate(
    val type: Int,
    val packageName: String?,
    val hasRoot: Boolean,
    val active: Boolean,
    val focused: Boolean,
    val layer: Int,
)
