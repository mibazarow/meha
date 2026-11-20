package com.andharness.ru.data.db

/**
 * Raw SQL of the token/cost ledger migration (update.md «Идеи следующих волн», idea #2): the
 * append-only `llm_usage` table plus its index on `at`.
 *
 * Kept as named constants (like [ToolAuditSchemaSql] / [SshSchemaSql]) so a pure JVM test can
 * cross-check them against the schema Room generates in `AppDatabase_Impl`
 * (`LlmUsageMigrationSqlTest`); Room re-validates the migrated database at runtime anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
 *
 * Non-destructive: it only creates a new table and its index; existing rows are untouched. Index
 * naming follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 *
 * The token columns are nullable: NULL is "the server did not report this number", never zero.
 */
object LlmUsageSchemaSql {

    const val CREATE_LLM_USAGE_TABLE =
        "CREATE TABLE IF NOT EXISTS `llm_usage` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`at` INTEGER NOT NULL, " +
            "`chatId` INTEGER, " +
            "`model` TEXT NOT NULL, " +
            "`promptTokens` INTEGER, " +
            "`completionTokens` INTEGER, " +
            "`totalTokens` INTEGER, " +
            "`source` TEXT NOT NULL, " +
            "`routed` INTEGER NOT NULL)"

    /** Newest-first reads and the day-range report are ordered/filtered by `at`. */
    const val CREATE_LLM_USAGE_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_llm_usage_at` ON `llm_usage` (`at`)"

    /** Every statement of the v21 -> v22 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_LLM_USAGE_TABLE,
        CREATE_LLM_USAGE_AT_INDEX,
    )
}
