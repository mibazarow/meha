package com.andharness.ru.bg

import com.andharness.ru.data.objective.ObjectiveRunCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bridge so [ObjectiveWorker] can reach the coordinator without a custom WorkerFactory. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ObjectiveWorkerEntryPoint {
    fun objectiveCoordinator(): ObjectiveRunCoordinator
}
