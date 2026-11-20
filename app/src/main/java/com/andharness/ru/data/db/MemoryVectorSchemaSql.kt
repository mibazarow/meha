package com.andharness.ru.data.db

/**
 * Raw SQL of the dense memory-embedding migration 28 -> 29: the `memory_vectors`
 * table (little-endian float32 `BLOB` + model tag) plus its index on `memoryId`, and the legacy
 * `settings.embeddingModel` column kept only for schema compatibility.
 *
 * Kept as named constants (like [RagChunkSchemaSql]) so a pure JVM test can cross-check them against
 * the schema Room generates in `AppDatabase_Impl` — Room re-validates the migrated database at
 * runtime anyway (https://developer.android.com/training/data-storage/room/migrating-db-versions);
 * the test just catches drift earlier and without a device.
 *
 * Non-destructive: it only creates a new table and its index, and ADD COLUMNs the settings column
 * with a default; every existing row is untouched. Index naming follows Room's convention
 * `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object MemoryVectorSchemaSql {

    const val CREATE_MEMORY_VECTORS_TABLE =
        "CREATE TABLE IF NOT EXISTS `memory_vectors` (" +
            "`memoryId` INTEGER NOT NULL, " +
            "`dim` INTEGER NOT NULL, " +
            "`vector` BLOB NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`memoryId`))"

    /** Lookup/join key back to `notes.id`; one vector per memory. */
    const val CREATE_MEMORY_VECTORS_MEMORY_ID_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_memory_vectors_memoryId` " +
            "ON `memory_vectors` (`memoryId`)"

    /**
     * Legacy, unused placeholder kept for schema compatibility; the real switch is
     * `semanticSearchEnabled`.
     */
    const val ADD_SETTINGS_EMBEDDING_MODEL_COLUMN =
        "ALTER TABLE settings ADD COLUMN embeddingModel TEXT NOT NULL DEFAULT ''"

    /** Every statement of the v28 -> v29 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_MEMORY_VECTORS_TABLE,
        CREATE_MEMORY_VECTORS_MEMORY_ID_INDEX,
        ADD_SETTINGS_EMBEDDING_MODEL_COLUMN,
    )
}
