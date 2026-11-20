package com.andharness.ru.data.db

/**
 * Raw SQL of the on-device semantic-search migration (v29 -> v30): the boolean
 * `settings.semanticSearchEnabled` column that turns the bundled offline embedder on.
 *
 * Kept as a named constant (like [ScheduledTaskSchemaSql]) so a pure JVM test can cross-check it
 * against the schema Room generates in `AppDatabase_Impl`
 * ([SemanticSearchMigrationSqlTest]) — Room re-validates the migrated database at runtime anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions); the test just
 * catches drift earlier and without a device.
 *
 * Non-destructive: it only adds a column with the default; existing rows keep their settings and
 * land on `semanticSearchEnabled = 0`, which the later v32 -> v33 migration flips to on.
 */
object SemanticSearchSchemaSql {

    /**
     * Turns the bundled on-device semantic search on. `INTEGER NOT NULL DEFAULT 0` matches how Room
     * maps a non-null Kotlin `Boolean` (`INTEGER NOT NULL`); the DEFAULT keeps existing rows off.
     */
    const val ADD_SEMANTIC_SEARCH_ENABLED_COLUMN =
        "ALTER TABLE settings ADD COLUMN semanticSearchEnabled INTEGER NOT NULL DEFAULT 0"

    /** Every statement of the v29 -> v30 migration, in execution order (test aid). */
    val all: List<String> = listOf(ADD_SEMANTIC_SEARCH_ENABLED_COLUMN)
}
