package com.andharness.ru.data.db

/**
 * DDL for the `llm_providers` table added in migration v41 -> v42. Kept as data (like the other
 * `*SchemaSql` objects) so a JVM test can cross-check it against the SQL Room generates from
 * [com.andharness.ru.data.db.entity.LlmProviderEntity] — the table must match exactly, otherwise
 * Room's schema validation fails at runtime.
 *
 * Non-destructive: only a new table is created; every existing row in `settings` and elsewhere is
 * untouched (the sibling `ALTER TABLE settings ADD COLUMN activeProviderId INTEGER` is nullable).
 *
 * Reference: https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
object LlmProviderSchemaSql {
    val all: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `llm_providers` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`endpoint` TEXT NOT NULL, " +
            "`model` TEXT NOT NULL, " +
            "`authStyle` TEXT NOT NULL, " +
            "`authHeaderName` TEXT NOT NULL, " +
            "`authQueryParam` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL)"
    )
}
