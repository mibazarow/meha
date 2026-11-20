package com.andharness.ru.data.autofill

import kotlinx.serialization.Serializable

/**
 * The user's OWN contact profile, used only to autofill forms the user themselves is filling.
 *
 * Deliberately minimal (name / phone / e-mail, all optional): the personal base stores OTHER people
 * and has no owner profile, so addresses, cards and other people's data are intentionally not
 * supported. A profile with no field set means autofill is effectively disabled (no datasets).
 */
@Serializable
data class AutofillProfile(
    val fullName: String? = null,
    val phone: String? = null,
    val email: String? = null,
) {
    /** Trimmed, non-blank value for [field], or null when the user has not set it. */
    fun valueFor(field: AutofillField): String? = when (field) {
        AutofillField.NAME -> fullName
        AutofillField.PHONE -> phone
        AutofillField.EMAIL -> email
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** True when no field is set — the autofill service then offers no datasets. */
    val isEmpty: Boolean
        get() = AutofillField.values().all { valueFor(it) == null }
}
