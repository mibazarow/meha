package com.andharness.ru.data.crash

import com.andharness.ru.data.db.dao.CrashLogDao
import com.andharness.ru.data.db.entity.CrashLogEntity
import com.andharness.ru.domain.crash.CrashLog
import com.andharness.ru.domain.crash.CrashLogRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CrashLogRepositoryImpl @Inject constructor(
    private val dao: CrashLogDao
) : CrashLogRepository {

    private fun toDomain(e: CrashLogEntity) = CrashLog(
        id = e.id,
        at = e.at,
        exceptionType = e.exceptionType,
        stack = e.stack,
        appVersion = e.appVersion,
        deviceModel = e.deviceModel,
        apiLevel = e.apiLevel,
        wasForeground = e.wasForeground,
        restartNumber = e.restartNumber
    )

    override suspend fun record(log: CrashLog): Long = dao.insert(
        CrashLogEntity(
            at = log.at,
            exceptionType = log.exceptionType,
            stack = log.stack,
            appVersion = log.appVersion,
            deviceModel = log.deviceModel,
            apiLevel = log.apiLevel,
            wasForeground = log.wasForeground,
            restartNumber = log.restartNumber
        )
    )

    override suspend fun latest(limit: Int): List<CrashLog> =
        dao.getLatest(limit.coerceAtLeast(1)).map(::toDomain)

    override suspend fun mostRecent(): CrashLog? = dao.getMostRecent()?.let(::toDomain)

    override suspend fun count(): Int = dao.count()

    override suspend fun clear(): Int {
        val before = dao.count()
        dao.deleteAll()
        return before
    }

    override suspend fun prune(now: Long) {
        dao.deleteOlderThan(now - CrashLogRepository.MAX_AGE_MS)
        dao.deleteBeyondLimit(CrashLogRepository.MAX_ENTRIES)
    }
}
