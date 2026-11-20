package com.andharness.ru.bg

import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.memory.MemoryVectorRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so [MemoryEmbeddingWorker] can reach its dependencies without a custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryEmbeddingEntryPoint {
    fun memoryVectorRepository(): MemoryVectorRepository
    fun powerGuard(): PowerGuard
    fun llmSettingsStore(): LlmSettingsStore
}
