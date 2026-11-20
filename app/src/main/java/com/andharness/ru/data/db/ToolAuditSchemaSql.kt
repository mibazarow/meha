package com.andharness.ru.data.db

/**
 * Raw SQL of the tool-call audit-log migration (update.md «Идеи следующих волн», idea #6): the
 * append-only `tool_audit` table plus its index on `at`.
 *
 * Kept as named constants (like [SshSchemaSql]) so a pure JVM test can cross-check them against the
 * schema Room generates in `AppDatabase_Impl` (`ToolAuditMigrationSqlTest`) — Room re-validates the
 * migrated database at runtime anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions), the test just
 * catches drift earlier and without a device.
 *
 * Non-destructive: it only creates a new table and its index; existing rows are untouched. Index
 * naming follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object ToolAuditSchemaSql {

    const val CREATE_TOOL_AUDIT_TABLE =
        "CREATE TABLE IF NOT EXISTS `tool_audit` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`at` INTEGER NOT NULL, " +
            "`chatId` INTEGER, " +
            "`tool` TEXT NOT NULL, " +
            "`argsPreview` TEXT NOT NULL, " +
            "`success` INTEGER NOT NULL, " +
            "`resultHash` TEXT NOT NULL, " +
            "`durationMs` INTEGER NOT NULL, " +
            "`model` TEXT, " +
            "`prevHash` TEXT NOT NULL, " +
            "`hash` TEXT NOT NULL)"

    /** Newest-first reads are ordered by `at`. */
    const val CREATE_TOOL_AUDIT_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_tool_audit_at` ON `tool_audit` (`at`)"

    /** Every statement of the v20 -> v21 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_TOOL_AUDIT_TABLE,
        CREATE_TOOL_AUDIT_AT_INDEX,
    )
}
