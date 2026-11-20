package com.andharness.ru.domain.calls

import com.andharness.ru.domain.person.IdentityNormalizer

/**
 * Pure, format-agnostic matching of a user-supplied phone argument against the raw `NUMBER` a row of
 * the call log stores.
 *
 * Why raw string equality is not enough:
 * - `CallLog.Calls.NUMBER` is documented as "The phone number as the user entered it", so the same
 *   subscriber can be stored as `89119617998`, `+7 911 961-79-98` or `(911) 961 79 98`. A SQL
 *   `CallLog.Calls.NUMBER = ?` therefore silently matches nothing when the input format differs.
 *   https://developer.android.com/reference/android/provider/CallLog.Calls#NUMBER
 * - The platform comparator for this is `PhoneNumberUtils.compare(String, String)` (deprecated as of
 *   API 31 in favour of `areSamePhoneNumber`) / `PhoneNumberUtils.normalizeNumber`:
 *   https://developer.android.com/reference/android/telephony/PhoneNumberUtils
 *   This app deliberately does NOT use it: [IdentityNormalizer] is a JVM-pure, dependency-free
 *   normalizer (no `android.*`), so the matching rule stays unit-testable and identical to the rest
 *   of the app's phone identity resolution.
 *
 * Matching compares the last [IdentityNormalizer.MATCH_KEY_DIGITS] significant digits of both sides
 * (the RU national number), which absorbs country-code, trunk-prefix and separator differences. A
 * blank/unparseable number yields no key and never matches.
 */
object CallLogNumberMatcher {

    /**
     * `true` when [input] and [stored] denote the same phone number after digit-based normalization.
     * Returns `false` when either side is blank, `null` or has no significant digits (formatting
     * characters only), so callers can treat "no match" honestly instead of deleting everything.
     */
    fun matches(input: String, stored: String?): Boolean {
        val inputKey = IdentityNormalizer.phoneMatchKey(input) ?: return false
        val storedKey = IdentityNormalizer.phoneMatchKey(stored) ?: return false
        return inputKey == storedKey
    }
}
