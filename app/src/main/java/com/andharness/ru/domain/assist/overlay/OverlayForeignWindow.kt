package com.andharness.ru.domain.assist.overlay

/**
 * Picks which window's root the overlay should read as "what is on screen".
 *
 * The overlay's own windows are excluded by package, never by type: an app overlay
 * (`TYPE_APPLICATION_OVERLAY`) is reported by `AccessibilityWindowInfo` as `TYPE_APPLICATION`, so a
 * type-only filter would happily return the bubble/panel as if it were the foreground app. Reporting
 * our own window would fabricate the foreground package, which this feature must never do
 * (BUG-038 honesty rule).
 *
 * Preference order among the readable foreign application windows: the active one first, then the
 * focused one, then the topmost by layer. The type filter keeps the IME and system windows (status
 * bar, navigation bar) from being mistaken for the foreground app.
 *
 * References:
 * - `AccessibilityService.getWindows()` (needs `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`) —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()
 * - `AccessibilityWindowInfo.getType()/isActive()/isFocused()/getLayer()/getRoot()` —
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo
 */
object OverlayForeignWindow {

    /** Mirrors `AccessibilityWindowInfo.TYPE_APPLICATION`. */
    const val TYPE_APPLICATION = 1

    /** Mirrors `AccessibilityWindowInfo.TYPE_INPUT_METHOD`. */
    const val TYPE_INPUT_METHOD = 2

    /** Mirrors `AccessibilityWindowInfo.TYPE_SYSTEM`. */
    const val TYPE_SYSTEM = 3

    /** Mirrors `AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY`. */
    const val TYPE_ACCESSIBILITY_OVERLAY = 4

    /**
     * True when [packageName] belongs to some other app. A blank/unknown package is NOT foreign:
     * the capture must not attribute an unreadable window to the foreground app.
     */
    fun isForeign(packageName: String?, ownPackage: String?): Boolean =
        !packageName.isNullOrBlank() && packageName != ownPackage

    /**
     * Index (into the list handed to `getWindows()`) of the foreign application window to read, or
     * `null` when none is readable — the caller then reports an honest "screen unreadable" context.
     */
    fun pickIndex(
        candidates: List<OverlayWindowCandidate>,
        ownPackage: String?,
    ): Int? = candidates
        .withIndex()
        .filter { (_, c) ->
            c.type == TYPE_APPLICATION && c.hasRoot && isForeign(c.packageName, ownPackage)
        }
        .sortedWith(
            compareByDescending<IndexedValue<OverlayWindowCandidate>> { it.value.active }
                .thenByDescending { it.value.focused }
                .thenByDescending { it.value.layer }
        )
        .firstOrNull()
        ?.index
}
