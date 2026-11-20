package com.andharness.ru.data.memory

import com.andharness.ru.domain.memory.DeviceFact
import com.andharness.ru.domain.memory.DeviceFactRepository
import com.andharness.ru.domain.memory.DeviceFactSpecs
import com.andharness.ru.domain.memory.DeviceFactSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/refresh/forget front-end for the device-fact cache (TZ §12.3): combines [DeviceFactRepository]
 * with [DeviceFactCollectors] so callers (tools, prompt builder, invalidation hooks) never have to
 * know how a fact is produced.
 *
 * Freshness follows the repository rule: `!invalidated && (ttlMs == 0 || now - updatedAt <= ttlMs)`.
 */
@Singleton
class DeviceFactStore @Inject constructor(
    private val repository: DeviceFactRepository,
    private val collectors: DeviceFactCollectors
) {

    /** The stored fact with [key], or `null` when it was never cached. */
    suspend fun cached(key: String): DeviceFact? = repository.get(key)

    /** All stored facts, stale ones included. */
    suspend fun all(): List<DeviceFact> = repository.all()

    /** Whether [key] is present, not invalidated and within its TTL. */
    suspend fun isFresh(key: String, now: Long = System.currentTimeMillis()): Boolean =
        DeviceFactSpecs.isCacheable(key) && repository.isFresh(key, now)

    /**
     * Re-produces [key] from its real Android source and stores it.
     *
     * @return the refreshed fact, or `null` when the key is unknown or the fact is unavailable
     *   right now (missing permission/hardware) — nothing is written in that case.
     */
    suspend fun refresh(key: String): DeviceFact? {
        val collected = collectors.collect(key) ?: return null
        repository.put(collected.toDomain())
        return repository.get(collected.key)
    }

    /** Refreshes every cacheable fact that can be produced right now. */
    suspend fun refreshAll(): List<DeviceFact> {
        return collectors.collectAll().mapNotNull { collected ->
            repository.put(collected.toDomain())
            repository.get(collected.key)
        }
    }

    /**
     * Refreshes the facts that must be re-produced at [now] (invalidated or past their TTL).
     * Used by the periodic sweep worker.
     */
    suspend fun refreshExpired(now: Long = System.currentTimeMillis()): List<DeviceFact> {
        val expired = repository.expired(now)
        return expired.mapNotNull { fact ->
            if (!DeviceFactSpecs.isCacheable(fact.key)) return@mapNotNull null
            refresh(fact.key)
        }
    }

    /** Removes one fact and journals the reason. Returns `false` when nothing was stored. */
    suspend fun forget(key: String, reason: String = REASON_MANUAL): Boolean {
        val existed = repository.get(key) != null
        if (existed) {
            repository.invalidate(key, reason)
            repository.remove(key)
        }
        return existed
    }

    /** Removes every stored fact, journaling [reason]. Returns the number of removed facts. */
    suspend fun forgetAll(reason: String = REASON_MANUAL): Int {
        val count = repository.all().size
        repository.clear(reason)
        return count
    }

    /** The compact prompt block for the currently fresh summary facts (empty when none are fresh). */
    suspend fun summary(now: Long = System.currentTimeMillis()): String =
        DeviceFactSummary.render(repository.all(), now)

    /** The invalidation journal (audit trail). */
    suspend fun events(limit: Int = 20) = repository.events(limit)

    private fun CollectedFact.toDomain() = DeviceFact(
        key = key,
        category = category,
        valueJson = valueJson,
        updatedAt = System.currentTimeMillis(),
        ttlMs = ttlMs,
        source = source,
        sourceApi = sourceApi,
        invalidated = false
    )

    companion object {
        const val REASON_MANUAL = "MANUAL"
    }
}
