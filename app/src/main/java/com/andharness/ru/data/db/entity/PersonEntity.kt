package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One person of the user's "unified personal base" (update.md §4.3, table `persons`).
 *
 * A person is a container that ties together identities (phone/email/messenger handles), aliases
 * and interactions. It is deliberately generic: a person may exist without any identity (created by
 * hand by the agent/user) and its [displayName] is not required to be unique — the resolver, not the
 * schema, decides "which Серёга" is meant.
 *
 * No foreign keys are declared on purpose so that the incremental ingester can write rows in any
 * order and a merge/purge never fails on constraint ordering; referential cleanup is done by the
 * repositories.
 */
@Entity(
    tableName = "persons",
    indices = [Index("displayName")]
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Human-readable name, as found in contacts or given by the user. Not unique. */
    val displayName: String,
    /** Free-form notes; empty string when none. */
    val notes: String,
    /** Epoch millis when the person row was first created. */
    val createdAt: Long,
    /** Epoch millis when the person row was last changed. */
    val updatedAt: Long
)
