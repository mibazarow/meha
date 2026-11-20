package com.andharness.ru.data.db

/**
 * Raw SQL of the one-shot-reminder migration (v25 -> v26): the nullable `atMs` instant on
 * `scheduled_tasks` (non-null ⇒ the task fires once instead of recurring).
 *
 * Kept as a named constant (like [ToolAuditSchemaSql]) so a pure JVM test can cross-check it against
 * the schema Room generates in `AppDatabase_Impl` (`ScheduledTaskMigrationSqlTest`) — Room
 * re-validates the migrated database at runtime anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions), the test just
 * catches drift earlier and without a device.
 *
 * Non-destructive: it only adds a nullable column; existing rows keep `atMs = NULL` and stay
 * recurring, so no data is dropped and no column is rewritten.
 */
object ScheduledTaskSchemaSql {

    /** Adds the one-shot instant; nullable, so existing recurring schedules are unaffected. */
    const val ADD_AT_MS_COLUMN = "ALTER TABLE scheduled_tasks ADD COLUMN atMs INTEGER"
}
