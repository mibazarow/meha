package com.andharness.ru.domain.locale

/**
 * Applies an interface language to the running app immediately (per-app locale), recreating the
 * visible Activity so the change takes effect without a manual restart.
 *
 * Extracted as an interface so a ViewModel can request the switch without depending on AndroidX
 * directly, and so the choice flow is unit-testable with a fake. The production binding forwards to
 * `com.andharness.ru.domain.locale.AppLocale.apply` (`AppCompatDelegate.setApplicationLocales`) — see
 * `com.andharness.ru.di.LocaleModule`.
 *
 * Docs: per-app language preferences —
 * https://developer.android.com/guide/topics/resources/app-languages
 */
fun interface LocaleApplier {
    /** Applies [language] (a BCP-47 tag, e.g. `"ru"` / `"en"`) as the app locale. */
    fun apply(language: String)
}
