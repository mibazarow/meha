package com.andharness.ru.data.memory


/**
 * A freshly produced device fact, ready to be persisted by [DeviceFactStore].
 *
 * @param source logical producer of the fact (always `device_facts_refresh`).
 * @param sourceApi concrete Android API used to produce it, for the audit trail (§12.5).
 */
data class CollectedFact(
    val key: String,
    val category: String,
    val valueJson: String,
    val ttlMs: Long,
    val source: String,
    val sourceApi: String?
)
