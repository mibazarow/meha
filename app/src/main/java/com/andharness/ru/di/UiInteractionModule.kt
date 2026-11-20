package com.andharness.ru.di

import com.andharness.ru.data.ui.UiInteractionBridgeImpl
import com.andharness.ru.domain.ui.UiInteractionBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UiInteractionModule {

    @Binds
    @Singleton
    abstract fun bindUiInteractionBridge(impl: UiInteractionBridgeImpl): UiInteractionBridge
}
