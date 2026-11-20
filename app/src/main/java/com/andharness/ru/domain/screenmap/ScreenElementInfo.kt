package com.andharness.ru.domain.screenmap

import kotlinx.serialization.Serializable

/**
 * One notable interactive element remembered for a screen: the fields an agent needs to recognise a
 * control later (text / content description / view id / class) plus a short [role] guessed from the
 * live node flags (`input` | `button` | `scroll` | `text`). Deliberately Android-free so the whole
 * model, its codec and the store are exercised on the JVM.
 */
@Serializable
data class ScreenElementInfo(
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    /** `input` when editable, `button` when clickable, `scroll` when scrollable, else `text`. */
    val role: String = ROLE_TEXT,
) {
    companion object {
        const val ROLE_INPUT = "input"
        const val ROLE_BUTTON = "button"
        const val ROLE_SCROLL = "scroll"
        const val ROLE_TEXT = "text"
    }
}
