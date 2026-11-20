package com.andharness.ru.data.db

/**
 * Raw SQL of the local RAG index migration (update.md idea #4): the `rag_chunks` table plus its
 * index on `sourcePath`.
 *
 * Kept as named constants (like [ToolAuditSchemaSql]) so a pure JVM test can cross-check them against
 * the schema Room generates in `AppDatabase_Impl` — Room re-validates the migrated database at runtime
 * anyway (https://developer.android.com/training/data-storage/room/migrating-db-versions); the test
 * just catches drift earlier and without a device.
 *
 * Non-destructive: it only creates a new table and its index; every existing row is untouched. Index
 * naming follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object RagChunkSchemaSql {

    const val CREATE_RAG_CHUNKS_TABLE =
        "CREATE TABLE IF NOT EXISTS `rag_chunks` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`sourcePath` TEXT NOT NULL, " +
            "`sourceType` TEXT NOT NULL, " +
            "`chunkIndex` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "`sizeBytes` INTEGER NOT NULL)"

    /** Per-source delete/replace and prefix filters are keyed by `sourcePath`. */
    const val CREATE_RAG_CHUNKS_SOURCE_PATH_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_rag_chunks_sourcePath` ON `rag_chunks` (`sourcePath`)"

    /** Every statement of the v23 -> v24 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_RAG_CHUNKS_TABLE,
        CREATE_RAG_CHUNKS_SOURCE_PATH_INDEX,
    )
}
