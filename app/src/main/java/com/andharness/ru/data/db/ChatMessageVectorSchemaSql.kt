package com.andharness.ru.data.db

/**
 * Raw SQL of the chat-message embedding store added in the Room migration 38 -> 39:
 * `chat_message_vectors` (little-endian float32 `BLOB` + model tag, keyed by `messages.id`) plus its
 * index on `model`. Mirrors [StoredMessageSchemaSql]'s `stored_message_vectors` half, so the same
 * [com.andharness.ru.domain.retrieval.VectorCodec] encoding and staleness-by-model semantics apply
 * to the app's own chat transcripts.
 *
 * Kept as named constants (like [StoredMessageSchemaSql]) so a pure JVM test can cross-check them
 * without a device; Room re-validates the migrated database at runtime
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
 *
 * Every statement is non-destructive: it only creates a new table and its index. The index name
 * follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices). The DDL is
 * ordinary SQLite (no vendor extensions), so it is SQLCipher-compatible.
 */
object ChatMessageVectorSchemaSql {

    const val CREATE_CHAT_MESSAGE_VECTORS_TABLE =
        "CREATE TABLE IF NOT EXISTS `chat_message_vectors` (" +
            "`messageId` INTEGER NOT NULL, " +
            "`dim` INTEGER NOT NULL, " +
            "`vector` BLOB NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`messageId`))"

    const val CREATE_CHAT_MESSAGE_VECTORS_MODEL_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_chat_message_vectors_model` " +
            "ON `chat_message_vectors` (`model`)"

    /** Every statement of the v38 -> v39 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_CHAT_MESSAGE_VECTORS_TABLE,
        CREATE_CHAT_MESSAGE_VECTORS_MODEL_INDEX,
    )
}
