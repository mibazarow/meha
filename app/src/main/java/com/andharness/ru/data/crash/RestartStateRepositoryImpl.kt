package com.andharness.ru.data.crash

import com.andharness.ru.data.db.dao.RestartStateDao
import com.andharness.ru.data.db.entity.RestartStateEntity
import com.andharness.ru.domain.crash.RestartState
import com.andharness.ru.domain.crash.RestartStateRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestartStateRepositoryImpl @Inject constructor(
    private val dao: RestartStateDao
) : RestartStateRepository {

    override suspend fun get(): RestartState {
        val entity = dao.get() ?: return RestartState()
        return RestartState(
            restartCount = entity.restartCount,
            windowStartAt = entity.windowStartAt,
            lastRestartAt = entity.lastRestartAt
        )
    }

    override suspend fun save(state: RestartState) {
        dao.upsert(
            RestartStateEntity(
                restartCount = state.restartCount,
                windowStartAt = state.windowStartAt,
                lastRestartAt = state.lastRestartAt
            )
        )
    }
}
