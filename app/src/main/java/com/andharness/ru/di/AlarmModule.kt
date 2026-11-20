package com.andharness.ru.di

import com.andharness.ru.data.alarm.ExactAlarmRepositoryImpl
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmModule {

    @Binds
    @Singleton
    abstract fun bindExactAlarmRepository(impl: ExactAlarmRepositoryImpl): ExactAlarmRepository
}
