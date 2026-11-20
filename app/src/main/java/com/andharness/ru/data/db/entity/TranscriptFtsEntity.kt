package com.andharness.ru.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4 full-text index over transcript segments: cheap `MATCH` candidate lookup instead of a full
 * scan of every transcript on each search.
 *
 * Content-backed: [Fts4.contentEntity] points at [TranscriptSegmentEntity], so the real text lives
 * in `transcript_segments` and `transcript_fts` only holds the search index; the FTS `rowid` equals
 * `transcript_segments.id`. Room generates the index-sync triggers (see
 * [com.andharness.ru.data.db.TranscriptFtsSchemaSql]).
 *
 * `unicode61` is chosen deliberately (same reason as [NoteFtsEntity]): the built-in `simple`
 * tokenizer case-folds ASCII only, so Russian text would not match case-insensitively.
 *
 * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
 * Reference: https://developer.android.com/reference/androidx/room/Fts4
 */
@Fts4(
    contentEntity = TranscriptSegmentEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "transcript_fts")
data class TranscriptFtsEntity(
    @ColumnInfo(name = "text") val text: String
)
