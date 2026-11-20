package com.andharness.ru.data.db

/**
 * Raw SQL of the persistent automation event log: the append-only
 * `automation_events` table plus its index on `createdAt`.
 *
 * Kept as named constants (like [ToolAuditSchemaSql]) so a pure JVM test can cross-check them
 * against the schema Room generates in `AppDatabase_Impl` (`AutomationEventMigrationSqlTest`) —
 * Room re-validates the migrated database at runtime anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions), the test just
 * catches drift earlier and without a device.
 *
 * Non-destructive: it only creates a new table and its index; existing rules/events/etc. are
 * untouched. Index naming follows Room's convention `index_<table>_<col>`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
object AutomationEventSchemaSql {

    /** Column order must match [AutomationEventEntity] declaration order. */
    const val CREATE_AUTOMATION_EVENTS_TABLE =
        "CREATE TABLE IF NOT EXISTS `automation_events` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`eventType` TEXT NOT NULL, " +
            "`value` TEXT, " +
            "`payload` TEXT, " +
            "`ruleId` INTEGER, " +
            "`ruleName` TEXT, " +
            "`createdAt` INTEGER NOT NULL)"

    /** Newest-first reads are ordered by `createdAt`. */
    const val CREATE_AUTOMATION_EVENTS_CREATED_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_automation_events_createdAt` " +
            "ON `automation_events` (`createdAt`)"

    /** Every statement of the v26 -> v27 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_AUTOMATION_EVENTS_TABLE,
        CREATE_AUTOMATION_EVENTS_CREATED_AT_INDEX,
    )
}
