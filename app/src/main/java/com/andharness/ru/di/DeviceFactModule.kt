package com.andharness.ru.di

import com.andharness.ru.data.memory.DeviceFactRepositoryImpl
import com.andharness.ru.domain.memory.DeviceFactRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceFactModule {

    @Binds
    @Singleton
    abstract fun bindDeviceFactRepository(impl: DeviceFactRepositoryImpl): DeviceFactRepository
}
