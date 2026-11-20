package com.andharness.ru.data.autofill

import java.util.Locale

/**
 * Pure mapping from an autofillable field's metadata to the owner-profile field it should be filled
 * with, or `null` when the service should not offer anything. It has no Android imports, so it is
 * exercised on the JVM by `AutofillHintsTest`.
 *
 * Matching order for a hint token:
 * 1. e-mail, 2. phone, 3. name — each first as an exact (normalized) token, then by a conservative
 * substring fallback. Normalization lowercases and strips everything but letters/digits, so
 * `person-full-name`, `PERSON_FULL_NAME` and `personFullName` all become `personfullname`.
 *
 * Hint values follow the official Android / AndroidX constants:
 * `AUTOFILL_HINT_EMAIL_ADDRESS = "emailAddress"`, `AUTOFILL_HINT_PHONE = "phone"`,
 * `AUTOFILL_HINT_PHONE_NUMBER = "phoneNumber"`, `AUTOFILL_HINT_PHONE_NATIONAL = "phoneNational"`,
 * `AUTOFILL_HINT_NAME = "name"`, `AUTOFILL_HINT_PERSON_NAME = "personName"`.
 *
 * References:
 * - https://developer.android.com/reference/android/view/View#AUTOFILL_HINT_NAME
 * - https://developer.android.com/reference/androidx/autofill/HintConstants
 * - https://developer.android.com/identity/autofill/autofill-services
 */
object AutofillHints {

    /** Exact normalized `android.view.View.AUTOFILL_HINT_*` tokens for a person name. */
    private val NAME_TOKENS = setOf(
        "name", "fullname", "personname", "personfullname", "displayname",
        "givenname", "familyname", "firstname", "lastname", "nickname",
    )

    /** Exact normalized `android.view.View.AUTOFILL_HINT_*` and HTML `tel*` tokens. */
    private val PHONE_TOKENS = setOf(
        "phone", "phonenumber", "phonenational", "phonenumberdevice", "phonecountrycode",
        "tel", "telephone", "telnational", "telcountrycode", "mobile", "mobilenumber", "cellphone",
    )

    /** Exact normalized `android.view.View.AUTOFILL_HINT_*` and HTML `email` tokens. */
    private val EMAIL_TOKENS = setOf(
        "email", "emailaddress", "mail", "mailaddress",
    )

    /**
     * Resolves the target field from the node's declared autofill hints.
     *
     * When the client app declared hints, they are authoritative: if none of them maps to a field
     * we serve, the answer is `null` (we must not guess into a field the app explicitly typed as
     * something else, e.g. a `username`/`password` pair).
     */
    fun fieldForHints(hints: Array<String>?): AutofillField? {
        if (hints.isNullOrEmpty()) return null
        for (raw in hints) {
            val token = normalize(raw) ?: continue
            val field = when {
                token in EMAIL_TOKENS || token.contains("email") -> AutofillField.EMAIL
                token in PHONE_TOKENS || token.contains("phone") || token.startsWith("tel") ->
                    AutofillField.PHONE
                token in NAME_TOKENS -> AutofillField.NAME
                else -> null
            }
            if (field != null) return field
        }
        return null
    }

    /**
     * Heuristic fallback used only when the app provided no usable autofill hints, as the framework
     * guide prescribes (`getHint()`/`getText()`). Substrings cover both Android-style English labels
     * and the Russian UI labels this app targets.
     */
    fun fieldForText(text: String?): AutofillField? {
        val value = text?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isEmpty()) return null
        // Never claim a username/login label: the profile has no username.
        if (value.contains("user") || value.contains("login") || value.contains("логин") ||
            value.contains("польз")
        ) return null
        return when {
            value.contains("email") || value.contains("e-mail") || value.contains("mail") ||
                value.contains("почт") || value.contains("мейл") -> AutofillField.EMAIL
            value.contains("phone") || value.contains("tel") ||
                value.contains("телефон") || value.contains("мобильн") -> AutofillField.PHONE
            value.contains("имя") || value.contains("фио") || value.contains("name") ->
                AutofillField.NAME
            else -> null
        }
    }

    /**
     * Combined decision for one node: declared hints win; the label text is consulted only when the
     * app gave no usable hints.
     */
    fun match(hints: Array<String>?, hintText: String?): AutofillField? {
        if (hints != null && hints.isNotEmpty()) return fieldForHints(hints)
        return fieldForText(hintText)
    }

    private fun normalize(raw: String): String? =
        raw.lowercase(Locale.US).filter { it.isLetterOrDigit() }.ifEmpty { null }
}
