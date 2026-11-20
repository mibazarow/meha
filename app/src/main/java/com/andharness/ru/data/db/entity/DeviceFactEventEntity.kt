package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry of the device-fact invalidation journal (TZ §12.3), recording why a fact went
 * stale (a package was installed, a volume was unmounted, the network changed, ...).
 * Belongs to [DeviceFactEntity] via [key] (table `device_fact_events`).
 */
@Entity(
    tableName = "device_fact_events",
    indices = [Index(value = ["key"]), Index(value = ["at"])]
)
data class DeviceFactEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Key of the affected fact ([DeviceFactEntity.key]). */
    val key: String,
    /** Human-readable cause of the invalidation. */
    val reason: String,
    /** Epoch millis when the invalidation was recorded. */
    val at: Long
)
