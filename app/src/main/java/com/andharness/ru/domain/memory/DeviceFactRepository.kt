package com.andharness.ru.domain.memory

/**
 * Persistence for device facts and their invalidation journal. Implemented on top of the Room
 * `device_facts` / `device_fact_events` tables; the schema change that introduced them ships with
 * an explicit migration (no destructive fallback), see
 * https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
interface DeviceFactRepository {

    /** The fact with [key], or `null` when it was never stored. */
    suspend fun get(key: String): DeviceFact?

    /** All stored facts of [category]. */
    suspend fun getCategory(category: String): List<DeviceFact>

    /** All stored facts. */
    suspend fun all(): List<DeviceFact>

    /** Stores [fact], stamping a fresh `updatedAt` and clearing its `invalidated` flag. */
    suspend fun put(fact: DeviceFact)

    /** Invalidates the fact with [key] and journals [reason]. */
    suspend fun invalidate(key: String, reason: String)

    /** Invalidates every fact of [category] and journals [reason]. */
    suspend fun invalidateCategory(category: String, reason: String)

    /** Invalidates every stored fact and journals [reason]. */
    suspend fun invalidateAll(reason: String)

    /** Whether the fact with [key] is present, not invalidated and within its TTL at [now]. */
    suspend fun isFresh(key: String, now: Long): Boolean

    /** Facts that must be re-produced: explicitly invalidated or past their TTL at [now]. */
    suspend fun expired(now: Long): List<DeviceFact>

    /** Removes the fact with [key] (the journal is kept as an audit trail). */
    suspend fun remove(key: String)

    /** Removes every fact, journaling [reason] for each. */
    suspend fun clear(reason: String)

    /** The most recent journal entries, newest first, capped by [limit]. */
    suspend fun events(limit: Int): List<DeviceFactEvent>
}
