package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Join row linking one long-term memory (`notes.id` of a `kind="memory"` row) to one
 * [PersonEntity] (`persons.id`), so recall can surface "memories about this person" and the
 * person card can show what the assistant remembers about them.
 *
 * [noteId] + [personId] form the composite primary key (one weighted link per pair, so a
 * re-observation replaces the row); [weight] is a relative strength used to rank the links of a
 * memory; [createdAt] is the epoch-millis write time. The index on `personId` serves the reverse
 * lookup "which memories mention this person".
 *
 * No foreign keys, consistent with `persons` / `person_aliases`, which declare none on purpose.
 * Schema/column names must match [com.andharness.ru.data.db.MemoryPersonSchemaSql] used by the Room
 * migration 30 -> 31. Official reference:
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "memory_persons",
    primaryKeys = ["noteId", "personId"],
    indices = [Index("personId")]
)
data class MemoryPersonEntity(
    val noteId: Long,
    val personId: Long,
    val weight: Float,
    val createdAt: Long
)
