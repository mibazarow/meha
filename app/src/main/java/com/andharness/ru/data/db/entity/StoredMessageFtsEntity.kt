package com.andharness.ru.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4 full-text index over stored messages: cheap `MATCH` candidate lookup instead of a full scan of
 * every message on each search.
 *
 * Content-backed: [Fts4.contentEntity] points at [StoredMessageEntity], so the real text lives in
 * `stored_messages` and `stored_message_fts` only holds the search index; the FTS `rowid` equals
 * `stored_messages.id`. Room generates the index-sync triggers (see
 * [com.andharness.ru.data.db.StoredMessageFtsSchemaSql]).
 *
 * `unicode61` is chosen deliberately (same reason as [TranscriptFtsEntity]): the built-in `simple`
 * tokenizer case-folds ASCII only, so Russian text would not match case-insensitively.
 *
 * Reference: https://developer.android.com/training/data-storage/room/defining-data#fts
 * Reference: https://developer.android.com/reference/androidx/room/Fts4
 */
@Fts4(
    contentEntity = StoredMessageEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "stored_message_fts")
data class StoredMessageFtsEntity(
    @ColumnInfo(name = "text") val text: String
)
