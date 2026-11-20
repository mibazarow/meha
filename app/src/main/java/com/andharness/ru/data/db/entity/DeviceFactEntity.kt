package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted device fact (TZ §12, "device facts" / self-knowledge), i.e. a stable or
 * semi-stable piece of knowledge about this phone and its environment (installed apps,
 * storage volumes, current network, battery, ...). The current value is stored as a JSON
 * string so each fact type keeps its own payload shape.
 */
@Entity(tableName = "device_facts")
data class DeviceFactEntity(
    /** Stable identifier of the fact, e.g. `device.model` or `apps.installed`. */
    @PrimaryKey
    val key: String,
    /** Fact family used for bulk invalidation, e.g. `apps`, `storage`, `network`. */
    val category: String,
    /** Serialized payload of the fact (opaque to the storage layer). */
    val valueJson: String,
    /** Epoch millis when the value was last produced/refreshed. */
    val updatedAt: Long,
    /** Freshness budget in millis; `0` means the fact is never cached (always volatile). */
    val ttlMs: Long,
    /** Logical producer of the fact, e.g. a tool name. */
    val source: String,
    /** Concrete Android API used to produce the fact, when known. */
    val sourceApi: String?,
    /** Set when the fact was invalidated by an event hook and must be re-produced. */
    val invalidated: Boolean
)
