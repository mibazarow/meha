package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One timestamped utterance of a recording's transcript (table `transcript_segments`).
 *
 * Segments are the unit the UI highlights and the unit the `transcript_vectors` table embeds; the
 * lexical FTS index (`transcript_fts`) is content-backed by **this** table, so `rowid` of the FTS
 * row equals [id] and the search index is rebuilt from `text` alone.
 *
 * [speakerId] is a soft link to `speakers.id` (no foreign key on purpose, matching
 * [PersonEntity]'s rationale) and stays `null` until diarisation attributes the utterance.
 * [confidence] is the recogniser's score in `[0, 1]`, when the engine reports one.
 *
 * Official reference:
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "transcript_segments",
    indices = [
        Index(value = ["recordingId", "startMs"]),
        Index("speakerId")
    ]
)
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Owning recording (`recordings.id`); segments are deleted with it. */
    val recordingId: Long,
    /** Offset of the utterance start from the beginning of the audio, in millis. */
    val startMs: Long,
    /** Offset of the utterance end from the beginning of the audio, in millis. */
    val endMs: Long,
    /** Recognised text; this is what the content-backed FTS4 index indexes. */
    val text: String,
    /** Soft link to `speakers.id` once diarisation assigned a speaker. */
    val speakerId: Long?,
    /** Recogniser confidence in `[0, 1]`, when reported. */
    val confidence: Float?,
    /** Epoch millis when the row was created. */
    val createdAt: Long
)
