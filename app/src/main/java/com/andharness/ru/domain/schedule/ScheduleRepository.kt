package com.andharness.ru.domain.schedule

import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeAll(): Flow<List<ScheduledTask>>
    suspend fun getAll(): List<ScheduledTask>
    suspend fun getById(id: Long): ScheduledTask?
    suspend fun create(task: ScheduledTask): Long
    suspend fun update(task: ScheduledTask)
    suspend fun delete(id: Long): Boolean
    suspend fun setEnabled(id: Long, enabled: Boolean): Boolean
    suspend fun markRun(id: Long, timestamp: Long)

    /**
     * Atomically claims the occurrence [slot] for `id`; `true` when this caller won the claim and
     * must run it, `false` when that occurrence already ran (a WorkManager re-delivery).
     */
    suspend fun claimRun(id: Long, slot: Long): Boolean
}
