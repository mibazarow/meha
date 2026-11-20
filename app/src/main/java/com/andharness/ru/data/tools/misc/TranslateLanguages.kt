package com.andharness.ru.data.tools.misc


/**
 * Bounded allow-list of translation languages: canonical ISO-639-1 codes mapped to the accepted
 * aliases (the code itself plus the common Russian and English names). Pure — no Android APIs — so
 * it is unit-tested on the JVM. The tool uses it to REJECT an unknown language honestly instead of
 * passing a garbage code to the LLM, which would otherwise invent a plausible-looking translation.
 *
 * Code reference: ISO 639-1 (https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes).
 */
internal object TranslateLanguages {

    /** Canonical code -> accepted aliases (lower-case): the code itself and RU/EN names. */
    val SUPPORTED: Map<String, List<String>> = linkedMapOf(
        "ru" to listOf("ru", "russian", "русский"),
        "en" to listOf("en", "english", "английский"),
        "de" to listOf("de", "german", "немецкий"),
        "fr" to listOf("fr", "french", "французский"),
        "es" to listOf("es", "spanish", "испанский"),
        "it" to listOf("it", "italian", "итальянский"),
        "pt" to listOf("pt", "portuguese", "португальский"),
        "zh" to listOf("zh", "zh-cn", "zh-hans", "chinese", "китайский"),
        "ja" to listOf("ja", "japanese", "японский"),
        "ko" to listOf("ko", "korean", "корейский"),
        "ar" to listOf("ar", "arabic", "арабский"),
        "tr" to listOf("tr", "turkish", "турецкий"),
        "uk" to listOf("uk", "ukrainian", "украинский"),
        "pl" to listOf("pl", "polish", "польский"),
        "nl" to listOf("nl", "dutch", "нидерландский", "голландский"),
        "sv" to listOf("sv", "swedish", "шведский"),
        "he" to listOf("he", "hebrew", "иврит"),
        "hi" to listOf("hi", "hindi", "хинди"),
    )

    /** A few codes shown to the user/model when a language is rejected. */
    const val EXAMPLES: String = "ru, en, de, fr, es"

    /** Alias (lower-case) -> canonical code, flattened from [SUPPORTED]. */
    private val ALIASES: Map<String, String> =
        buildMap { for ((code, aliases) in SUPPORTED) for (alias in aliases) put(alias, code) }

    /** Source-language values that mean "detect automatically". */
    private val AUTO_ALIASES: Set<String> = setOf("auto", "авто", "automatically")

    /**
     * Resolves a user/model language string to its canonical code, or `null` when it cannot be
     * recognized. Case-insensitive; `_` is treated as `-`, and a region suffix is
     * dropped (`en-US` -> `en`), so `"ZH-Hans"` resolves to `zh`. Pure and unit-tested.
     */
    fun resolve(input: String?): String? {
        val key = input?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (key.isEmpty()) return null
        ALIASES[key]?.let { return it }
        return ALIASES[key.substringBefore('-')]
    }

    /**
     * True when the source language should be auto-detected: missing/blank input or an explicit
     * `auto`/`авто` marker. Pure and unit-tested.
     */
    fun isAuto(input: String?): Boolean {
        val key = input?.trim()?.lowercase() ?: return true
        return key.isEmpty() || key in AUTO_ALIASES
    }
}
