package com.andharness.ru.bg

import com.andharness.ru.data.offline.OfflineActionQueue
import com.andharness.ru.domain.tools.ToolRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so [OfflineActionWorker] needs no custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OfflineActionWorkerEntryPoint {
    fun offlineActionQueue(): OfflineActionQueue
    fun toolRegistry(): ToolRegistry
}
