package com.andharness.ru.bg

import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.domain.assist.AssistRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so [MemoryGcWorker] can reach the repository without a custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryGcEntryPoint {
    fun assistRepository(): AssistRepository
    fun powerGuard(): PowerGuard
}
