package com.andharness.ru.domain.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Thin wrapper over `AppCompatDelegate` for the in-app RU/EN language switch.
 *
 * `AppCompatDelegate.setApplicationLocales()` is the documented backward-compatible API for
 * per-app language preferences: on Android 13+ (API 33+) it delegates to the framework
 * `LocaleManager`, and on Android 12 and lower (API 31–32 here, given `minSdk = 31`) AppCompat
 * provides the implementation. Persisting is handled by AppCompat: automatic storage on API ≤ 32 is
 * enabled by the `AppLocalesMetadataHolderService` `autoStoreLocales` manifest entry, and the
 * framework stores it on API 33+.
 *
 * Docs:
 * - Per-app language preferences (in-app picker, `autoStoreLocales`, first-run corner cases) —
 *   https://developer.android.com/guide/topics/resources/app-languages
 * - `AppCompatDelegate.setApplicationLocales` / `getApplicationLocales` —
 *   https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate#setApplicationLocales(androidx.core.os.LocaleListCompat)
 * - `LocaleListCompat.forLanguageTags` —
 *   https://developer.android.com/reference/androidx/core/os/LocaleListCompat#forLanguageTags(java.lang.String)
 * - Framework `LocaleManager` (API 33+) —
 *   https://developer.android.com/reference/android/app/LocaleManager
 */
object AppLocale {

    /**
     * Applies [language] as the app's locale immediately. The platform recreates the visible
     * activities so the change takes effect without a manual restart, and it stays applied on the
     * next launch (framework storage on 33+, AppCompat `autoStoreLocales` on ≤ 32).
     */
    fun apply(language: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(AppLanguage.normalize(language))
        )
    }

    /**
     * Establishes the stored/default language at startup before any UI composes.
     *
     * On a first launch nothing has been chosen yet, so `getApplicationLocales()` is empty: applying
     * the stored default (Russian) there makes RU the effective default even on a device whose
     * system language is English — this is the documented "call setApplicationLocales the first time
     * your app is run" corner case. Once a language has been set (by this app, or by the user from
     * system settings), the existing choice is respected and not overridden.
     *
     * Safe to call before any Activity exists: the request is stored and picked up when the first
     * Activity attaches. Must run on the main thread.
     */
    fun ensureDefault(storedLanguage: String) {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            apply(storedLanguage)
        }
    }
}
