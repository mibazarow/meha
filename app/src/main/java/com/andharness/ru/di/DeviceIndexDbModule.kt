package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.DeviceIndexVectorDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Provider for the whole-device semantic index DAO (`device_index_vectors`). */
@Module
@InstallIn(SingletonComponent::class)
object DeviceIndexDbModule {

    @Provides
    fun provideDeviceIndexVectorDao(database: AppDatabase): DeviceIndexVectorDao =
        database.deviceIndexVectorDao()
}
