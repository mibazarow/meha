package com.andharness.ru.data.schedule

import com.andharness.ru.data.db.dao.ScheduledTaskDao
import com.andharness.ru.data.db.entity.ScheduledTaskEntity
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduledTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleRepositoryImpl @Inject constructor(
    private val dao: ScheduledTaskDao
) : ScheduleRepository {

    private fun toDomain(e: ScheduledTaskEntity) = ScheduledTask(
        id = e.id,
        prompt = e.prompt,
        hour = e.hour,
        minute = e.minute,
        daysMask = e.daysMask,
        enabled = e.enabled,
        lastRunAt = e.lastRunAt,
        createdAt = e.createdAt,
        chatId = e.chatId,
        atMs = e.atMs
    )

    private fun toEntity(t: ScheduledTask) = ScheduledTaskEntity(
        id = t.id,
        prompt = t.prompt,
        hour = t.hour,
        minute = t.minute,
        daysMask = t.daysMask,
        enabled = t.enabled,
        lastRunAt = t.lastRunAt,
        createdAt = t.createdAt,
        chatId = t.chatId,
        atMs = t.atMs
    )

    override fun observeAll(): Flow<List<ScheduledTask>> = dao.observeAll().map { list -> list.map(::toDomain) }

    override suspend fun getAll(): List<ScheduledTask> = dao.getAll().map(::toDomain)

    override suspend fun getById(id: Long): ScheduledTask? = dao.getById(id)?.let(::toDomain)

    override suspend fun create(task: ScheduledTask): Long = dao.insert(toEntity(task))

    override suspend fun update(task: ScheduledTask) = dao.update(toEntity(task))

    override suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    override suspend fun setEnabled(id: Long, enabled: Boolean): Boolean {
        val existing = dao.getById(id) ?: return false
        dao.update(existing.copy(enabled = enabled))
        return true
    }

    override suspend fun markRun(id: Long, timestamp: Long) = dao.setLastRun(id, timestamp)

    override suspend fun claimRun(id: Long, slot: Long): Boolean = dao.claimRun(id, slot) > 0
}
