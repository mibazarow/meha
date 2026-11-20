package com.andharness.ru.di

import com.andharness.ru.domain.locale.AppLocale
import com.andharness.ru.domain.locale.LocaleApplier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the per-app locale applier used by the first-run language chooser.
 *
 * The domain [LocaleApplier] seam keeps the ViewModel free of AndroidX; here it is backed by the
 * real `AppCompatDelegate.setApplicationLocales` wrapper ([AppLocale]). Bindable so tests inject a
 * fake instead.
 *
 * Docs: per-app language preferences —
 * https://developer.android.com/guide/topics/resources/app-languages
 */
@Module
@InstallIn(SingletonComponent::class)
object LocaleModule {

    @Provides
    @Singleton
    fun provideLocaleApplier(): LocaleApplier = LocaleApplier { language -> AppLocale.apply(language) }
}
