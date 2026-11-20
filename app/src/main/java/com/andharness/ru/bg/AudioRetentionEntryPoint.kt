package com.andharness.ru.bg

import com.andharness.ru.data.communications.CommunicationsConfigStore
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.domain.communications.RecordingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge so [AudioRetentionGcWorker] can reach the Communications graph without a custom
 * WorkerFactory.
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioRetentionEntryPoint {
    fun communicationsConfigStore(): CommunicationsConfigStore
    fun recordingsRepository(): RecordingsRepository
    fun powerGuard(): PowerGuard
}
