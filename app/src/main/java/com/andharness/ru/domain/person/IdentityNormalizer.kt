package com.andharness.ru.domain.person

import java.util.Locale

/**
 * Pure, dependency-free normalization of person identities (update.md §4.3, "правило нормализации").
 *
 * The normalized value is what gets stored in `person_identities.value` and is the primary matching
 * key of the resolver (update.md §4.5, step 1 "нормализация + точное совпадение"). It is deliberately
 * conservative and honest — anything that cannot be interpreted as a valid handle of its type
 * returns `null` instead of being mangled into a fake value:
 *
 * - **phone** → E.164-like `+<digits>`; an 11-digit number starting with `8` and a bare 10-digit
 *   number are expanded with the default country code (Russia, `+7`), a leading `00` is treated as
 *   the international prefix. [phoneMatchKey] additionally returns the last 10 significant digits,
 *   which is what local (RU) matching uses;
 * - **email** → trimmed and lower-cased, with a structural sanity check (`local@domain.tld`);
 * - **messenger** → trimmed within its package (the same handle in two apps stays distinct because
 *   the app package is part of the identity key);
 * - **other** → trimmed.
 *
 * Like [com.andharness.ru.data.osint.NumberNormalizer], this intentionally avoids `libphonenumber`
 * and `android.telephony.PhoneNumberUtils` so it stays a JVM-pure, unit-testable function.
 *
 * Official references:
 * - `Character.isDigit`: https://developer.android.com/reference/java/lang/Character#isDigit(int)
 * - `String.trim` / `Locale` casing: https://developer.android.com/reference/java/lang/String
 * - E.164 number format: ITU-T E.164 (public knowledge).
 */
object IdentityNormalizer {

    /** The app package used for identities that have no owning app (phone/email/other). */
    const val NO_PACKAGE = ""

    /** Russia, the primary locale of the app; used only to expand clearly-local numbers. */
    private const val DEFAULT_COUNTRY_CODE = "7"

    private const val MIN_PHONE_DIGITS = 5
    private const val MAX_PHONE_DIGITS = 15

    /** How many trailing digits of a phone number are used for local matching (RU national number). */
    const val MATCH_KEY_DIGITS = 10

    /**
     * Normalizes [value] for its [type], or returns `null` when it is not a valid handle.
     *
     * @param appPackage the package a messenger handle belongs to; it does not change the normalized
     *   value today (the current rule trims uniformly) but it is part of the identity key and the
     *   documented hook for future per-package rules.
     */
    fun normalize(type: IdentityType, value: String?, appPackage: String = NO_PACKAGE): String? =
        when (type) {
            IdentityType.PHONE -> normalizePhone(value)
            IdentityType.EMAIL -> normalizeEmail(value)
            IdentityType.MESSENGER -> normalizeMessenger(value)
            IdentityType.OTHER -> normalizeText(value)
        }

    /**
     * Normalizes a phone number to a stable `+<digits>` form, or returns `null` when [raw] cannot be
     * interpreted as a number (empty, alphanumeric sender, too short/too long, no country context).
     */
    fun normalizePhone(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val hadPlus = trimmed.startsWith("+")
        val hadIntl00 = !hadPlus && trimmed.startsWith("00")

        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        if (hadIntl00) digits = digits.removePrefix("00")

        if (!hadPlus && !hadIntl00) {
            digits = when {
                digits.length == 11 && digits.startsWith("8") ->
                    DEFAULT_COUNTRY_CODE + digits.substring(1)
                digits.length == 10 -> DEFAULT_COUNTRY_CODE + digits
                else -> digits
            }
        }

        if (digits.length < MIN_PHONE_DIGITS || digits.length > MAX_PHONE_DIGITS) return null
        if (digits.startsWith("0")) return null
        return "+$digits"
    }

    /**
     * The local-matching key of a phone number: the last [MATCH_KEY_DIGITS] significant digits (the
     * RU/CA national number), or all digits when the number is shorter. Returns `null` when the
     * number itself cannot be normalized.
     *
     * Used when a raw number must be compared against an already-normalized identity without
     * assuming a country code.
     */
    fun phoneMatchKey(raw: String?): String? {
        val normalized = normalizePhone(raw) ?: return null
        val digits = normalized.removePrefix("+")
        return if (digits.length > MATCH_KEY_DIGITS) digits.takeLast(MATCH_KEY_DIGITS) else digits
    }

    /** Trimmed, lower-cased email with a structural sanity check; `null` when invalid. */
    fun normalizeEmail(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase(Locale.US).orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null
        if (trimmed.count { it == '@' } != 1) return null

        val local = trimmed.substringBefore('@')
        val domain = trimmed.substringAfter('@')
        if (local.isEmpty() || domain.isEmpty()) return null
        if (!domain.contains('.')) return null
        if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return null
        return trimmed
    }

    /**
     * Trimmed messenger handle. The package is not used here — it is part of the identity key
     * (`type` + `value` + `appPackage`), so the same handle in two apps never collides.
     */
    fun normalizeMessenger(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /** Generic trimmed text for [IdentityType.OTHER] handles. */
    fun normalizeText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
