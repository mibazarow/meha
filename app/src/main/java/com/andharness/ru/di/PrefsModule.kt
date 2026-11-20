package com.andharness.ru.di

import com.andharness.ru.data.prefs.LlmSettingsStoreImpl
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StoreModule {

    @Binds
    @Singleton
    abstract fun bindLlmSettingsStore(
        impl: LlmSettingsStoreImpl
    ): LlmSettingsStore
}
