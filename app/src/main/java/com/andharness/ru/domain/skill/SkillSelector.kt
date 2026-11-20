package com.andharness.ru.domain.skill

import kotlinx.serialization.Serializable

/**
 * Robust, human-readable description of an on-screen element. The future run engine resolves it
 * against the accessibility tree with this priority order: `resourceId` -> `contentDescription` ->
 * exact `text` -> `textContains`; [className] narrows the candidate set and [index] picks among
 * equal matches. Every field is optional (null = "do not constrain"), so a selector can be as loose
 * or as strict as the recording was.
 *
 * Field set and semantics deliberately mirror the already-landed semantic resolver
 * `com.andharness.ru.domain.ui.UiSelector` (+ `UiSelectorResolver`), so the engine can map a
 * [SkillSelector] to a `UiSelector` one-to-one. In particular [index] is a **1-based** ordinal among
 * the ranked matches (the same numbering `ui_find` prints); `null` means "best-ranked match".
 *
 * Accessibility node fields the engine will read:
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
 */

@Serializable
data class SkillSelector(
    val text: String? = null,
    val textContains: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val index: Int? = null,
    /** Human note ("кнопка Оплатить") shown in `skill_get`; never used for matching. */
    val description: String = "",
) {
    /** True when no matching field is set, so the selector can never resolve to an element. */
    fun isEmpty(): Boolean =
        text.isNullOrBlank() &&
            textContains.isNullOrBlank() &&
            resourceId.isNullOrBlank() &&
            contentDescription.isNullOrBlank() &&
            className.isNullOrBlank() &&
            index == null

    /** Convenience inverse of [isEmpty] for validation. */
    fun isUsable(): Boolean = !isEmpty()

    /**
     * True when at least one *identifying* field is set (text/desc/id), not only a structural one
     * ([className]/[index]). The engine requires an identifying field to match an element safely.
     */
    fun hasIdentity(): Boolean =
        !text.isNullOrBlank() ||
            !textContains.isNullOrBlank() ||
            !resourceId.isNullOrBlank() ||
            !contentDescription.isNullOrBlank()
}
