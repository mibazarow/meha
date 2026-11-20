package com.andharness.ru.domain.alarm

interface ExactAlarmRepository {
    suspend fun getAll(): List<ExactAlarm>
    suspend fun getById(id: Long): ExactAlarm?
    suspend fun create(alarm: ExactAlarm): Long
    suspend fun setRequestCode(id: Long, requestCode: Int)
    suspend fun markFired(id: Long, fired: Boolean)
    suspend fun delete(id: Long): Boolean
}
