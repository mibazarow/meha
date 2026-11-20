package com.andharness.ru.data.db

/**
 * Raw SQL of the generic entity graph migration 34 -> 35: the `entities` node table (unique
 * `normalizedName`) and the `memory_entities` join table (memory `noteId` + `entityId` composite key,
 * a `weight` and a `createdAt`) plus its index on `entityId`. Mirrors [MemoryPersonSchemaSql] for the
 * `memory ↔ person` graph.
 *
 * Kept as named constants so a pure JVM test can cross-check them against the schema Room generates
 * in `AppDatabase_Impl` — Room re-validates the migrated database at runtime anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions); the test just
 * catches drift earlier and without a device.
 *
 * Non-destructive: it only creates new tables and their indices; every existing row is untouched.
 * Index naming follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object MemoryEntitySchemaSql {

    const val CREATE_ENTITIES_TABLE =
        "CREATE TABLE IF NOT EXISTS `entities` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`normalizedName` TEXT NOT NULL, " +
            "`kind` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`lastSeenAt` INTEGER NOT NULL)"

    /** Canonical dedup key: one row per normalized topic/project/place. */
    const val CREATE_ENTITIES_NORMALIZED_NAME_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_entities_normalizedName` " +
            "ON `entities` (`normalizedName`)"

    const val CREATE_MEMORY_ENTITIES_TABLE =
        "CREATE TABLE IF NOT EXISTS `memory_entities` (" +
            "`noteId` INTEGER NOT NULL, " +
            "`entityId` INTEGER NOT NULL, " +
            "`weight` REAL NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`noteId`, `entityId`))"

    /** Reverse lookup "which memories mention this entity". */
    const val CREATE_MEMORY_ENTITIES_ENTITY_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_memory_entities_entityId` " +
            "ON `memory_entities` (`entityId`)"

    /** Every statement of the v34 -> v35 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_ENTITIES_TABLE,
        CREATE_ENTITIES_NORMALIZED_NAME_UNIQUE_INDEX,
        CREATE_MEMORY_ENTITIES_TABLE,
        CREATE_MEMORY_ENTITIES_ENTITY_INDEX,
    )
}
