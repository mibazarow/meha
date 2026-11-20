package com.andharness.ru.bg

import com.andharness.ru.data.diagnostics.PowerGuard
import com.andharness.ru.data.memory.DeviceFactStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so [DeviceFactsSweepWorker] can reach the cache without a custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeviceFactsSweepEntryPoint {
    fun deviceFactStore(): DeviceFactStore
    fun powerGuard(): PowerGuard
}
