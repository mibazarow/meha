package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One generic graph entity (topic / project / place / other), as opposed to a person. Memories link
 * to entities through [MemoryEntityLinkEntity], so graph-expanded recall can traverse
 * "memory ↔ entity" edges the same way it traverses "memory ↔ person" edges.
 *
 * [normalizedName] is the canonical dedup key (lower-case, single-spaced) and carries a unique index
 * so the same topic never produces two rows; [name] keeps the original casing for display. [kind] is
 * a short free-form tag (e.g. `topic`, `project`, `place`, `other`); no enum is enforced on purpose
 * so new kinds need no migration.
 *
 * No foreign keys, consistent with `persons` / `memory_persons`. Schema/column names must match
 * [com.andharness.ru.data.db.MemoryEntitySchemaSql] used by the Room migration 34 -> 35. Official
 * reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "entities",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class GenericEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Human-readable name, as first observed. */
    val name: String,
    /** Canonical dedup key: lower-case, single-spaced (unique). */
    val normalizedName: String,
    /** `topic` / `project` / `place` / `other`; free-form on purpose. */
    val kind: String,
    /** Epoch millis when the entity row was first created. */
    val createdAt: Long,
    /** Epoch millis when the entity was last observed in a memory. */
    val lastSeenAt: Long
)
