package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Join row linking one long-term memory (`notes.id` of a `kind="memory"` row) to one [GenericEntity]
 * (`entities.id`), mirroring [MemoryPersonEntity] for the generic entity graph.
 *
 * [noteId] + [entityId] form the composite primary key (one weighted link per pair, so a
 * re-observation replaces the row); [weight] is a relative strength used to rank the links of a
 * memory; [createdAt] is the epoch-millis write time. The index on `entityId` serves the reverse
 * lookup "which memories mention this entity".
 *
 * No foreign keys, consistent with `entities` / `memory_persons`. Schema/column names must match
 * [com.andharness.ru.data.db.MemoryEntitySchemaSql] used by the Room migration 34 -> 35. Official
 * reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "memory_entities",
    primaryKeys = ["noteId", "entityId"],
    indices = [Index("entityId")]
)
data class MemoryEntityLinkEntity(
    val noteId: Long,
    val entityId: Long,
    val weight: Float,
    val createdAt: Long
)
