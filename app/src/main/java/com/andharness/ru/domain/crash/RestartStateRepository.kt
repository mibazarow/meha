package com.andharness.ru.domain.crash

/** Single-row persistence for the restart guard ([RestartState]). */
interface RestartStateRepository {
    suspend fun get(): RestartState
    suspend fun save(state: RestartState)
}
