package com.andharness.ru.bg

import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.messages.StoredMessageVectorRepository
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge so [com.andharness.ru.data.messages.StoredMessageEmbeddingWorker] can reach its
 * dependencies without a custom WorkerFactory (same pattern as [MemoryEmbeddingEntryPoint]).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StoredMessageEmbeddingEntryPoint {
    fun storedMessageVectorRepository(): StoredMessageVectorRepository
    fun powerGuard(): PowerGuard
    fun llmSettingsStore(): LlmSettingsStore
}
