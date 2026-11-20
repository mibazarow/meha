package com.andharness.ru.domain.screenmap

/**
 * Pure selection of the "notable" elements of a screen from plain node data. Keeps controls a later
 * visit can act on (clickable / editable / scrollable, or a view with a resource id and a label) and
 * drops decorative containers; assigns a short [ScreenElementInfo.role] and runs the shared
 * dedup/cap in [ScreenMapCodec].
 *
 * Android-free: the caller maps live `AccessibilityNodeInfo` fields into [Raw] (see
 * `UiAutomationToolsHandler`), so the selection logic is unit-testable on the JVM.
 *
 * References (official Android docs):
 * - `AccessibilityNodeInfo.isClickable()` / `isEditable()` / `isScrollable()`:
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 */
object ScreenMapElements {

    /** Plain snapshot of one live node, mirroring the fields [ScreenElementInfo] stores. */
    data class Raw(
        val text: String? = null,
        val contentDescription: String? = null,
        val resourceId: String? = null,
        val className: String? = null,
        val clickable: Boolean = false,
        val editable: Boolean = false,
        val scrollable: Boolean = false,
    )

    /** Short role for a node: input (editable) > button (clickable) > scroll > text. */
    fun role(raw: Raw): String = when {
        raw.editable -> ScreenElementInfo.ROLE_INPUT
        raw.clickable -> ScreenElementInfo.ROLE_BUTTON
        raw.scrollable -> ScreenElementInfo.ROLE_SCROLL
        else -> ScreenElementInfo.ROLE_TEXT
    }

    /**
     * Keeps the notable controls and normalises/dedups/caps them. An element is notable when it is
     * interactive, or when it carries a resource id together with a human-readable label.
     */
    fun notable(raw: List<Raw>): List<ScreenElementInfo> {
        val kept = raw.filter { element ->
            element.clickable || element.editable || element.scrollable ||
                (!element.resourceId.isNullOrBlank() &&
                    (!element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank()))
        }
        return ScreenMapCodec.dedupeAndCap(
            kept.map { element ->
                ScreenElementInfo(
                    text = element.text,
                    contentDescription = element.contentDescription,
                    resourceId = element.resourceId,
                    className = element.className,
                    role = role(element),
                )
            },
        )
    }
}
