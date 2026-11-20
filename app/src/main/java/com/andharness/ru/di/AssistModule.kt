package com.andharness.ru.di

import com.andharness.ru.data.assist.AssistRepositoryImpl
import com.andharness.ru.domain.assist.AssistRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistModule {

    @Binds
    @Singleton
    abstract fun bindAssistRepository(impl: AssistRepositoryImpl): AssistRepository
}
