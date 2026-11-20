package com.andharness.ru.data.diagnostics

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge so background workers can consult the [PowerGuard] without a custom WorkerFactory
 * (update.md idea #19).
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PowerGuardEntryPoint {
    fun powerGuard(): PowerGuard
}
