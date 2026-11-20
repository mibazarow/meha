package com.andharness.ru.data.alarm

import com.andharness.ru.data.db.dao.ExactAlarmDao
import com.andharness.ru.data.db.entity.ExactAlarmEntity
import com.andharness.ru.domain.alarm.ExactAlarm
import com.andharness.ru.domain.alarm.ExactAlarmRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExactAlarmRepositoryImpl @Inject constructor(
    private val dao: ExactAlarmDao
) : ExactAlarmRepository {

    private fun toDomain(e: ExactAlarmEntity) = ExactAlarm(
        id = e.id,
        atMs = e.atMs,
        label = e.label,
        requestCode = e.requestCode,
        createdAt = e.createdAt,
        fired = e.fired,
        chatId = e.chatId
    )

    private fun toEntity(a: ExactAlarm) = ExactAlarmEntity(
        id = a.id,
        atMs = a.atMs,
        label = a.label,
        requestCode = a.requestCode,
        createdAt = a.createdAt,
        fired = a.fired,
        chatId = a.chatId
    )

    override suspend fun getAll(): List<ExactAlarm> = dao.getAll().map(::toDomain)

    override suspend fun getById(id: Long): ExactAlarm? = dao.getById(id)?.let(::toDomain)

    override suspend fun create(alarm: ExactAlarm): Long = dao.insert(toEntity(alarm))

    override suspend fun setRequestCode(id: Long, requestCode: Int) = dao.setRequestCode(id, requestCode)

    override suspend fun markFired(id: Long, fired: Boolean) = dao.setFired(id, fired)

    override suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0
}
