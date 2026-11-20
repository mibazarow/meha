package com.andharness.ru.data.db

/**
 * Raw SQL of the full-text messages store added in the Room migration 37 -> 38: `stored_messages`
 * (full message bodies + metadata) and `stored_message_vectors` (per-message embeddings) with their
 * indices. The FTS4 search index over stored messages lives in [StoredMessageFtsSchemaSql].
 *
 * The statements live here as named constants (like [CommunicationsSchemaSql]) so a pure JVM unit
 * test can cross-check them without a device. Room re-validates every migrated database at runtime
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions); this keeps a
 * drift visible earlier.
 *
 * Every statement is non-destructive: it only creates new tables/indices. Index names follow Room's
 * convention `index_<table>_<col>[_<col>...]`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices). The
 * `externalRef` index is UNIQUE so the same source event is never stored twice; SQLite treats `NULL`s
 * as distinct, so many rows without a ref are still allowed.
 */
object StoredMessageSchemaSql {

    // ---- stored_messages ----

    const val CREATE_STORED_MESSAGES_TABLE =
        "CREATE TABLE IF NOT EXISTS `stored_messages` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sourceType` TEXT NOT NULL, " +
            "`packageName` TEXT, " +
            "`conversationKey` TEXT, " +
            "`personId` INTEGER, " +
            "`direction` TEXT NOT NULL, " +
            "`sender` TEXT, " +
            "`isGroup` INTEGER NOT NULL, " +
            "`at` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL, " +
            "`externalRef` TEXT, " +
            "`createdAt` INTEGER NOT NULL)"

    const val CREATE_STORED_MESSAGES_SOURCE_TYPE_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_stored_messages_sourceType_at` " +
            "ON `stored_messages` (`sourceType`, `at`)"

    const val CREATE_STORED_MESSAGES_PERSON_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_stored_messages_personId_at` " +
            "ON `stored_messages` (`personId`, `at`)"

    const val CREATE_STORED_MESSAGES_PACKAGE_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_stored_messages_packageName_at` " +
            "ON `stored_messages` (`packageName`, `at`)"

    const val CREATE_STORED_MESSAGES_CONVERSATION_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_stored_messages_conversationKey_at` " +
            "ON `stored_messages` (`conversationKey`, `at`)"

    const val CREATE_STORED_MESSAGES_EXTERNAL_REF_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_stored_messages_externalRef` " +
            "ON `stored_messages` (`externalRef`)"

    // ---- stored_message_vectors ----

    const val CREATE_STORED_MESSAGE_VECTORS_TABLE =
        "CREATE TABLE IF NOT EXISTS `stored_message_vectors` (" +
            "`messageId` INTEGER NOT NULL, " +
            "`dim` INTEGER NOT NULL, " +
            "`vector` BLOB NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`messageId`))"

    const val CREATE_STORED_MESSAGE_VECTORS_MODEL_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_stored_message_vectors_model` " +
            "ON `stored_message_vectors` (`model`)"

    /** Every statement of the migration's table part, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_STORED_MESSAGES_TABLE,
        CREATE_STORED_MESSAGES_SOURCE_TYPE_AT_INDEX,
        CREATE_STORED_MESSAGES_PERSON_AT_INDEX,
        CREATE_STORED_MESSAGES_PACKAGE_AT_INDEX,
        CREATE_STORED_MESSAGES_CONVERSATION_AT_INDEX,
        CREATE_STORED_MESSAGES_EXTERNAL_REF_UNIQUE_INDEX,
        CREATE_STORED_MESSAGE_VECTORS_TABLE,
        CREATE_STORED_MESSAGE_VECTORS_MODEL_INDEX,
    )
}
