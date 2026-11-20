package com.andharness.ru.data.db

/**
 * The raw SQL of the "unified personal base" migrations (update.md §4.3): the `persons`,
 * `person_identities`, `interactions` and `person_aliases` tables with their indices.
 *
 * The statements live here as named constants instead of being inlined into the migrations so that
 * a pure JVM unit test can cross-check them, byte for byte, against the schema Room expects in the
 * kapt-generated `AppDatabase_Impl` (`PersonMigrationSqlTest`). Room itself re-validates every
 * migrated database at runtime
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions), so a drift here
 * would fail the migration anyway — the test merely catches it earlier and without a device.
 *
 * Every statement is non-destructive: it only creates new tables/indices.
 *
 * Index names follow Room's naming convention `index_<table>_<col>[_<col>...]`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object PersonSchemaSql {

    // ---- persons (v14 -> v15) ----

    const val CREATE_PERSONS_TABLE =
        "CREATE TABLE IF NOT EXISTS `persons` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`displayName` TEXT NOT NULL, " +
            "`notes` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)"

    const val CREATE_PERSONS_DISPLAY_NAME_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_persons_displayName` ON `persons` (`displayName`)"

    // ---- person_identities (v15 -> v16) ----

    const val CREATE_PERSON_IDENTITIES_TABLE =
        "CREATE TABLE IF NOT EXISTS `person_identities` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`personId` INTEGER NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`value` TEXT NOT NULL, " +
            "`appPackage` TEXT NOT NULL, " +
            "`label` TEXT)"

    const val CREATE_PERSON_IDENTITIES_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_person_identities_type_value_appPackage` " +
            "ON `person_identities` (`type`, `value`, `appPackage`)"

    const val CREATE_PERSON_IDENTITIES_PERSON_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_person_identities_personId` ON `person_identities` (`personId`)"

    // ---- interactions (v16 -> v17) ----

    const val CREATE_INTERACTIONS_TABLE =
        "CREATE TABLE IF NOT EXISTS `interactions` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`personId` INTEGER NOT NULL, " +
            "`channel` TEXT NOT NULL, " +
            "`direction` TEXT NOT NULL, " +
            "`at` INTEGER NOT NULL, " +
            "`title` TEXT, " +
            "`summary` TEXT, " +
            "`externalRef` TEXT, " +
            "`sourceApp` TEXT)"

    const val CREATE_INTERACTIONS_PERSON_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_interactions_personId_at` ON `interactions` (`personId`, `at`)"

    const val CREATE_INTERACTIONS_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_interactions_at` ON `interactions` (`at`)"

    // ---- person_aliases (v17 -> v18) ----

    const val CREATE_PERSON_ALIASES_TABLE =
        "CREATE TABLE IF NOT EXISTS `person_aliases` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`personId` INTEGER NOT NULL, " +
            "`alias` TEXT NOT NULL)"

    const val CREATE_PERSON_ALIASES_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_person_aliases_personId_alias` " +
            "ON `person_aliases` (`personId`, `alias`)"

    const val CREATE_PERSON_ALIASES_ALIAS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_person_aliases_alias` ON `person_aliases` (`alias`)"

    /** Every statement of the four migrations, in version order (test aid). */
    val all: List<String> = listOf(
        CREATE_PERSONS_TABLE,
        CREATE_PERSONS_DISPLAY_NAME_INDEX,
        CREATE_PERSON_IDENTITIES_TABLE,
        CREATE_PERSON_IDENTITIES_UNIQUE_INDEX,
        CREATE_PERSON_IDENTITIES_PERSON_INDEX,
        CREATE_INTERACTIONS_TABLE,
        CREATE_INTERACTIONS_PERSON_AT_INDEX,
        CREATE_INTERACTIONS_AT_INDEX,
        CREATE_PERSON_ALIASES_TABLE,
        CREATE_PERSON_ALIASES_UNIQUE_INDEX,
        CREATE_PERSON_ALIASES_ALIAS_INDEX,
    )
}
