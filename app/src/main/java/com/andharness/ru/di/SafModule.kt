package com.andharness.ru.di

import com.andharness.ru.data.saf.SafGrantRepositoryImpl
import com.andharness.ru.domain.saf.SafGrantRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SafModule {

    @Binds
    @Singleton
    abstract fun bindSafGrantRepository(impl: SafGrantRepositoryImpl): SafGrantRepository
}
