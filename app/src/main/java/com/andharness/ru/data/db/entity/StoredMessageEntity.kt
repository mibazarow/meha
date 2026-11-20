package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured message with its **full body** (table `stored_messages`): an SMS/MMS, or a
 * messenger/email/chat message captured from a notification. Unlike [InteractionEntity], which keeps
 * only metadata + a bounded summary, this table persists the complete [text] so messages can later be
 * searched by meaning and summarised across conversations.
 *
 * The lexical FTS4 index (`stored_message_fts`) is content-backed by **this** table, so the FTS
 * `rowid` equals [id] and the index is rebuilt from [text] alone. The per-message embedding lives in
 * `stored_message_vectors`, keyed by [id].
 *
 * [sourceType] is the `SMS` | `MMS` | `MESSENGER` | `EMAIL` | `CHAT` source kind; [direction] is
 * `INCOMING` | `OUTGOING` | `UNKNOWN`. [personId] is a soft link to `persons.id` (no foreign key, the
 * same rationale as [PersonEntity]); [externalRef] is the producer's own stable id (SMS `_id`,
 * notification key, ...) and carries a UNIQUE index so the same event is never stored twice — SQLite
 * treats `NULL`s as distinct, so many rows without a ref remain allowed.
 *
 * Official reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "stored_messages",
    indices = [
        Index(value = ["sourceType", "at"]),
        Index(value = ["personId", "at"]),
        Index(value = ["packageName", "at"]),
        Index(value = ["conversationKey", "at"]),
        Index(value = ["externalRef"], unique = true)
    ]
)
data class StoredMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Source kind: `SMS` | `MMS` | `MESSENGER` | `EMAIL` | `CHAT`. */
    val sourceType: String,
    /** Package of the app the message was captured from, when relevant. */
    val packageName: String?,
    /** Stable key of the conversation this message belongs to, when known. */
    val conversationKey: String?,
    /** Soft link to `persons.id` once the sender is resolved. */
    val personId: Long?,
    /** `INCOMING` | `OUTGOING` | `UNKNOWN`. */
    val direction: String,
    /** Display name / address of the sender, when available. */
    val sender: String?,
    /** Whether the message belongs to a group conversation. */
    val isGroup: Boolean,
    /** Epoch millis when the message happened (not when it was indexed). */
    val at: Long,
    /** Full message body; this is what the content-backed FTS4 index indexes and the embedder embeds. */
    val text: String,
    /** Producer's stable id of the source event (dedup key), when available. */
    val externalRef: String?,
    /** Epoch millis when the row was created. */
    val createdAt: Long
)
