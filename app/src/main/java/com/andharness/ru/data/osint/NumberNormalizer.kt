package com.andharness.ru.data.osint


/**
 * Pure, dependency-free normalization of phone numbers for the interception/OSINT pipeline
 * (update.md §2.4/§2.5).
 *
 * The app deliberately avoids `libphonenumber` (no extra dependency, and the OSINT engine only
 * needs a stable cache key + a best-effort international prefix). Everything here is honest and
 * conservative:
 * - only digits are kept, plus an optional leading `+`;
 * - a leading `00` is treated as the international access prefix;
 * - an 11-digit number starting with `8` and a bare 10-digit number are resolved with the
 *   default country code (Russia, `+7`), matching the primary target locale;
 * - alphanumeric senders (bank shortcodes, operator names) and too-short strings return `null`
 *   instead of being mangled into a fake number.
 *
 * The region/origin prefix table is a small heuristic, NOT a numbering plan: it is reported
 * exactly as a heuristic and never as a confirmed operator/carrier fact.
 *
 * References:
 * - `Character.isDigit`, `Locale.US` casing: https://developer.android.com/reference/java/lang/Character
 * - Android telephony number handling (`PhoneNumberUtils`) was intentionally avoided so this stays
 *   a JVM-pure, unit-testable function.
 */
object NumberNormalizer {

    /** Russia, the primary locale of the app; used only to expand clearly-local numbers. */
    private const val DEFAULT_COUNTRY_CODE = "7"

    private const val MIN_DIGITS = 5
    private const val MAX_DIGITS = 15

    /**
     * Normalizes [raw] to a stable `+<digits>` form, or returns `null` when [raw] cannot be
     * interpreted as a phone number (empty string, alphanumeric sender, too short/too long).
     */
    fun normalize(raw: String?): String? {
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

        if (digits.length < MIN_DIGITS || digits.length > MAX_DIGITS) return null
        if (digits.startsWith("0")) return null
        return "+$digits"
    }

    /** Human-readable, honest description of where the number *prefix* points (heuristic). */
    fun regionFor(normalized: String): String? {
        val digits = normalized.removePrefix("+")
        if (digits.isEmpty()) return null
        return PREFIX_REGIONS.entries
            .filter { digits.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /** True when [normalized] already looks like a canonical `+<digits>` value. */
    fun isNormalized(value: String?): Boolean =
        value != null && value.length >= MIN_DIGITS + 1 &&
            value.startsWith("+") && value.drop(1).all { it.isDigit() }

    /**
     * Longest-prefix table (country calling code → human label). Kept intentionally small and
     * labelled as a heuristic; unknown prefixes return `null` rather than a guess.
     * Source of calling codes: ITU-T E.164 public country-code list (public knowledge).
     */
    private val PREFIX_REGIONS: Map<String, String> = buildMap {
        put("7", "Россия/Казахстан (+7)")
        put("1", "Северная Америка (+1)")
        put("20", "Египет (+20)")
        put("27", "ЮАР (+27)")
        put("30", "Греция (+30)")
        put("31", "Нидерланды (+31)")
        put("32", "Бельгия (+32)")
        put("33", "Франция (+33)")
        put("34", "Испания (+34)")
        put("36", "Венгрия (+36)")
        put("39", "Италия (+39)")
        put("40", "Румыния (+40)")
        put("41", "Швейцария (+41)")
        put("43", "Австрия (+43)")
        put("44", "Великобритания (+44)")
        put("45", "Дания (+45)")
        put("46", "Швеция (+46)")
        put("47", "Норвегия (+47)")
        put("48", "Польша (+48)")
        put("49", "Германия (+49)")
        put("52", "Мексика (+52)")
        put("55", "Бразилия (+55)")
        put("60", "Малайзия (+60)")
        put("61", "Австралия (+61)")
        put("62", "Индонезия (+62)")
        put("63", "Филиппины (+63)")
        put("64", "Новая Зеландия (+64)")
        put("65", "Сингапур (+65)")
        put("66", "Таиланд (+66)")
        put("81", "Япония (+81)")
        put("82", "Южная Корея (+82)")
        put("84", "Вьетнам (+84)")
        put("86", "Китай (+86)")
        put("90", "Турция (+90)")
        put("91", "Индия (+91)")
        put("92", "Пакистан (+92)")
        put("94", "Шри-Ланка (+94)")
        put("351", "Португалия (+351)")
        put("353", "Ирландия (+353)")
        put("358", "Финляндия (+358)")
        put("370", "Литва (+370)")
        put("371", "Латвия (+371)")
        put("372", "Эстония (+372)")
        put("373", "Молдова (+373)")
        put("374", "Армения (+374)")
        put("375", "Беларусь (+375)")
        put("380", "Украина (+380)")
        put("420", "Чехия (+420)")
        put("880", "Бангладеш (+880)")
        put("966", "Саудовская Аравия (+966)")
        put("971", "ОАЭ (+971)")
        put("972", "Израиль (+972)")
        put("977", "Непал (+977)")
        put("992", "Таджикистан (+992)")
        put("994", "Азербайджан (+994)")
        put("995", "Грузия (+995)")
        put("996", "Киргизия (+996)")
        put("998", "Узбекистан (+998)")
    }
}
