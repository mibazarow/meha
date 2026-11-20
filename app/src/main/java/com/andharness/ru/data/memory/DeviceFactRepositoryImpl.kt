package com.andharness.ru.data.memory

import com.andharness.ru.data.db.dao.DeviceFactDao
import com.andharness.ru.data.db.entity.DeviceFactEntity
import com.andharness.ru.data.db.entity.DeviceFactEventEntity
import com.andharness.ru.domain.memory.DeviceFact
import com.andharness.ru.domain.memory.DeviceFactEvent
import com.andharness.ru.domain.memory.DeviceFactRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceFactRepositoryImpl @Inject constructor(
    private val dao: DeviceFactDao
) : DeviceFactRepository {

    override suspend fun get(key: String): DeviceFact? = dao.byKey(key)?.toDomain()

    override suspend fun getCategory(category: String): List<DeviceFact> =
        dao.byCategory(category).map { it.toDomain() }

    override suspend fun all(): List<DeviceFact> = dao.all().map { it.toDomain() }

    override suspend fun put(fact: DeviceFact) {
        dao.upsert(
            DeviceFactEntity(
                key = fact.key,
                category = fact.category,
                valueJson = fact.valueJson,
                updatedAt = System.currentTimeMillis(),
                ttlMs = fact.ttlMs,
                source = fact.source,
                sourceApi = fact.sourceApi,
                invalidated = false
            )
        )
    }

    override suspend fun invalidate(key: String, reason: String) {
        dao.markInvalidatedByKey(key)
        dao.insertEvent(
            DeviceFactEventEntity(key = key, reason = reason, at = System.currentTimeMillis())
        )
        pruneJournal()
    }

    override suspend fun invalidateCategory(category: String, reason: String) {
        val keys = dao.byCategory(category).map { it.key }
        dao.markInvalidatedByCategory(category)
        journal(keys, reason)
    }

    override suspend fun invalidateAll(reason: String) {
        val keys = dao.all().map { it.key }
        dao.markAllInvalidated()
        journal(keys, reason)
    }

    override suspend fun isFresh(key: String, now: Long): Boolean {
        val fact = dao.byKey(key) ?: return false
        if (fact.invalidated) return false
        return fact.ttlMs == 0L || now - fact.updatedAt <= fact.ttlMs
    }

    override suspend fun expired(now: Long): List<DeviceFact> =
        dao.expired(now).map { it.toDomain() }

    override suspend fun remove(key: String) = dao.delete(key)

    override suspend fun clear(reason: String) {
        val keys = dao.all().map { it.key }
        dao.deleteAll()
        journal(keys, reason)
    }

    override suspend fun events(limit: Int): List<DeviceFactEvent> =
        dao.recentEvents(limit).map { it.toDomain() }

    private suspend fun journal(keys: List<String>, reason: String) {
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            dao.insertEvent(DeviceFactEventEntity(key = key, reason = reason, at = now))
        }
        pruneJournal()
    }

    /**
     * Bounds the invalidation journal so `device_fact_events` cannot grow forever: drops entries
     * older than [EVENT_RETENTION_MS] and then everything but the newest [EVENT_RETENTION_COUNT].
     * Called after every write (the journal is only ever appended, never cleaned up elsewhere).
     * The `at` column is indexed (Room v20) so both deletes stay cheap.
     */
    private suspend fun pruneJournal() {
        dao.deleteEventsOlderThan(System.currentTimeMillis() - EVENT_RETENTION_MS)
        dao.pruneEventsToNewest(EVENT_RETENTION_COUNT)
    }

    private fun DeviceFactEntity.toDomain() = DeviceFact(
        key = key,
        category = category,
        valueJson = valueJson,
        updatedAt = updatedAt,
        ttlMs = ttlMs,
        source = source,
        sourceApi = sourceApi,
        invalidated = invalidated
    )

    private fun DeviceFactEventEntity.toDomain() = DeviceFactEvent(
        id = id,
        key = key,
        reason = reason,
        at = at
    )

    private companion object {
        /** Age bound of the invalidation journal. */
        const val EVENT_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L

        /** Count bound of the invalidation journal. */
        const val EVENT_RETENTION_COUNT = 200
    }
}
