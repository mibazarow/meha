package com.andharness.ru.domain.locale

/**
 * The languages the app UI is offered in. This is the pure, Android-free source of truth for the
 * in-app language switch: the persisted preference stores one of [SUPPORTED], and anything unknown
 * normalizes to [DEFAULT] (Russian), so the UI can never hold a value the app does not render.
 *
 * Only two languages are wired for now (the localization foundation). The full per-app locale list
 * declared in `res/xml/locales_config.xml` is intentionally wider, but only RU/EN have translated
 * resources today; the switch here exposes exactly those.
 *
 * Docs: the per-app language feature and the `LocaleConfig` it is declared alongside —
 * https://developer.android.com/guide/topics/resources/app-languages
 * https://developer.android.com/guide/topics/resources/app-languages#auto-localeconfig
 */
object AppLanguage {

    /** Russian. Also the app default: the base `values/` resources are Russian. */
    const val RUSSIAN: String = "ru"

    /** English (base tag; `en` covers `values-en`). */
    const val ENGLISH: String = "en"

    /** The default UI language. */
    const val DEFAULT: String = RUSSIAN

    /** Selectable languages, in the order shown in the UI. */
    val SUPPORTED: List<String> = listOf(RUSSIAN, ENGLISH)

    /**
     * Normalizes an arbitrary BCP-47 tag (e.g. one read back from the persisted preference or from
     * `AppCompatDelegate.getApplicationLocales()`) to a supported language tag: any English variant
     * (`en`, `en-US`, …) maps to [ENGLISH]; every other/unknown value maps to [DEFAULT] (Russian).
     */
    fun normalize(tag: String?): String {
        val base = tag?.trim()?.substringBefore('-')?.substringBefore('_')?.lowercase()
        return if (base == ENGLISH) ENGLISH else RUSSIAN
    }
}
