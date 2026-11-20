package com.andharness.ru.data.crash

import com.andharness.ru.data.diagnostics.PowerGuard
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so [RestartWorker] can consult the guard and re-schedule without a WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RestartWorkerEntryPoint {
    fun powerGuard(): PowerGuard
    fun restartScheduler(): RestartScheduler
}
