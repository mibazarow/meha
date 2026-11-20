package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audio recording tracked by the "Communications" feature (table `recordings`).
 *
 * The room only stores **metadata about the audio file** ([filePath], [displayName], [sizeBytes],
 * [durationMs]) plus the transcription pipeline state; the audio bytes themselves live on the file
 * system (app-private storage or a SAF grant) and are never copied into the database.
 *
 * [sourceType] and [transcriptStatus] hold the names of
 * [com.andharness.ru.domain.communications.RecordingSourceType] /
 * [com.andharness.ru.domain.communications.TranscriptStatus], not ordinals, so the schema survives
 * constant reordering. [personId] is a soft link to `persons.id` (no foreign key on purpose — see
 * [PersonEntity] — referential cleanup belongs to the repositories).
 *
 * Schema/column names must match [com.andharness.ru.data.db.CommunicationsSchemaSql] used by the
 * Room migration 35 -> 36. Official reference:
 * https://developer.android.com/training/data-storage/room/defining-data
 * Index-naming convention `index_<table>_<col>`:
 * https://developer.android.com/training/data-storage/room/defining-data#indices
 */
@Entity(
    tableName = "recordings",
    indices = [
        Index("transcriptStatus"),
        Index("createdAt"),
        Index("personId")
    ]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** [com.andharness.ru.domain.communications.RecordingSourceType] name. */
    val sourceType: String,
    /** Absolute path or SAF URI of the audio file; the bytes stay outside the DB. */
    val filePath: String,
    /** Human-readable label shown in the list, when known. */
    val displayName: String?,
    /** Audio length in milliseconds, `0` when not probed yet. */
    val durationMs: Long,
    /** File size in bytes, `0` when unknown. */
    val sizeBytes: Long,
    /** Package the file came from for [com.andharness.ru.domain.communications.RecordingSourceType.MESSENGER_VOICE]. */
    val sourcePackage: String?,
    /** [com.andharness.ru.domain.communications.TranscriptStatus] name. */
    val transcriptStatus: String,
    /** User asked to keep this recording regardless of retention policy. */
    val keepFlag: Boolean,
    /** Soft link to `persons.id` once the recording is attributed to a person. */
    val personId: Long?,
    /** Failure reason when [transcriptStatus] is `FAILED`; `null` otherwise. */
    val errorText: String?,
    /** Epoch millis when the row was first created. */
    val createdAt: Long,
    /** Epoch millis when the row was last changed. */
    val updatedAt: Long
)
