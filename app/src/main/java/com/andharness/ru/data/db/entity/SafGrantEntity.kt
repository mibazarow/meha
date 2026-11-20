package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted Storage Access Framework (SAF) tree grant — a user-selected folder the agent
 * may read/write in restricted mode (SPEC §5.4, FR-FILES-4/5). [uri] is the persisted tree URI
 * granted via `ACTION_OPEN_DOCUMENT_TREE` with the persistable read/write flags.
 */
@Entity(
    tableName = "saf_grants",
    indices = [Index(value = ["uri"], unique = true)]
)
data class SafGrantEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** The persisted tree URI string (unique). */
    val uri: String,
    /** The tree document id parsed from [uri] (used to build child document URIs). */
    val treeDocumentId: String,
    /** Human-readable folder name shown in the UI. */
    val displayName: String,
    val createdAt: Long,
    /** Last time the grant was re-checked (0 = never since it was added). */
    val lastCheckedAt: Long,
    /** Whether the last check still had a valid/accessible grant. */
    val lastOk: Boolean
)
