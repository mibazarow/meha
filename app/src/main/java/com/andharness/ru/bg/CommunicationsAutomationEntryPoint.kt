package com.andharness.ru.bg

import com.andharness.ru.data.communications.CommunicationsConfigStore
import com.andharness.ru.data.communications.RecordingsScanner
import com.andharness.ru.data.communications.TranscriptionService
import com.andharness.ru.data.communications.speech.SpeechModelStore
import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.domain.communications.RecordingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge so [CommunicationsAutomationWorker] can reach the Communications graph without a
 * custom WorkerFactory, mirroring [MemoryGcEntryPoint].
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CommunicationsAutomationEntryPoint {
    fun communicationsConfigStore(): CommunicationsConfigStore
    fun recordingsScanner(): RecordingsScanner
    fun transcriptionService(): TranscriptionService
    fun recordingsRepository(): RecordingsRepository
    fun speechModelStore(): SpeechModelStore
    fun powerGuard(): PowerGuard
}
