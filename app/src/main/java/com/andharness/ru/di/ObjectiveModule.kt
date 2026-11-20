package com.andharness.ru.di

import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.objective.ObjectiveRunnerImpl
import com.andharness.ru.domain.diagnostics.PowerStateProvider
import com.andharness.ru.domain.objective.ObjectiveRunner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings for background objective execution (checklist §2): the [ObjectiveRunner] implementation
 * the coordinator drives, and the read-only [PowerStateProvider] view of [PowerGuard] that keeps the
 * coordinator unit-testable (a fake snapshot replaces the Android class in JVM tests).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ObjectiveModule {

    @Binds
    @Singleton
    abstract fun bindObjectiveRunner(impl: ObjectiveRunnerImpl): ObjectiveRunner

    @Binds
    @Singleton
    abstract fun bindPowerStateProvider(guard: PowerGuard): PowerStateProvider
}
