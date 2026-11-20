package com.andharness.ru.data.db

/**
 * Raw SQL of the memory-person link migration 30 -> 31: the `memory_persons` join table
 * (memory `noteId` + `personId` composite key, a `weight` and a `createdAt`) plus its index on
 * `personId`.
 *
 * Kept as named constants (like [MemoryVectorSchemaSql]) so a pure JVM test can cross-check them
 * against the schema Room generates in `AppDatabase_Impl` — Room re-validates the migrated database
 * at runtime anyway (https://developer.android.com/training/data-storage/room/migrating-db-versions);
 * the test just catches drift earlier and without a device.
 *
 * Non-destructive: it only creates a new table and its index; every existing row is untouched.
 * Index naming follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object MemoryPersonSchemaSql {

    const val CREATE_MEMORY_PERSONS_TABLE =
        "CREATE TABLE IF NOT EXISTS `memory_persons` (" +
            "`noteId` INTEGER NOT NULL, " +
            "`personId` INTEGER NOT NULL, " +
            "`weight` REAL NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`noteId`, `personId`))"

    /** Reverse lookup "which memories mention this person". */
    const val CREATE_MEMORY_PERSONS_PERSON_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_memory_persons_personId` " +
            "ON `memory_persons` (`personId`)"

    /** Every statement of the v30 -> v31 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_MEMORY_PERSONS_TABLE,
        CREATE_MEMORY_PERSONS_PERSON_INDEX,
    )
}
