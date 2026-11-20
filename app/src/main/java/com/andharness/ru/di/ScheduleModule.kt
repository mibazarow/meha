package com.andharness.ru.di

import com.andharness.ru.data.schedule.ScheduleRepositoryImpl
import com.andharness.ru.data.schedule.ScheduleSchedulerImpl
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduleScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScheduleModule {

    @Binds
    @Singleton
    abstract fun bindScheduleRepository(impl: ScheduleRepositoryImpl): ScheduleRepository

    @Binds
    @Singleton
    abstract fun bindScheduleScheduler(impl: ScheduleSchedulerImpl): ScheduleScheduler
}
