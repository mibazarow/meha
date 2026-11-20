package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dense embedding of one of the app's OWN chat messages (table `chat_message_vectors`), stored so
 * the chat list / in-chat search can match by meaning. This mirrors the captured-message store
 * [StoredMessageVectorEntity]: the only difference is the key points at `messages.id` (a chat
 * transcript row) instead of `stored_messages.id` (a captured SMS/notification).
 *
 * One vector per message, hence [messageId] is the primary key rather than an auto-generated id.
 * [vector] is the little-endian float32 `BLOB` produced by
 * [com.andharness.ru.domain.retrieval.VectorCodec]; [dim] is its element count (cheap integrity
 * check); [model] records which embedding model produced it, so a model change makes the row stale
 * and the indexer re-embeds it.
 *
 * Schema/column names must match [com.andharness.ru.data.db.ChatMessageVectorSchemaSql]. Official
 * reference (entity / `@PrimaryKey` / `indices` / `ByteArray` → `BLOB`):
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "chat_message_vectors",
    indices = [Index("model")]
)
data class ChatMessageVectorEntity(
    @PrimaryKey
    val messageId: Long,
    val dim: Int,
    val vector: ByteArray,
    val model: String,
    val updatedAt: Long
)
