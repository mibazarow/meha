package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the one-shot-reminder migration (v25 -> v26) against the schema Room
 * itself generates, mirroring [ToolAuditMigrationSqlTest]: the `atMs` column added by
 * [ScheduledTaskSchemaSql.ADD_AT_MS_COLUMN] must match the nullable `scheduled_tasks.atMs` column
 * Room derives from [com.andharness.ru.data.db.entity.ScheduledTaskEntity], so a name/type/nullability
 * typo is caught without a device (Room re-validates the migrated schema at runtime anyway:
 * https://developer.android.com/training/data-storage/room/migrating-db-versions). Skipped (never
 * failed) when the kapt-generated file is unavailable.
 */
class ScheduledTaskMigrationSqlTest {

    @Test
    fun migrationAddsTheSameColumnRoomGenerates() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        val createTable = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            .singleOrNull { it.startsWith("CREATE TABLE IF NOT EXISTS $TABLE_MARKER") }
        assumeTrue(
            "generated schema does not contain scheduled_tasks yet (stale build); skipped",
            createTable != null
        )

        assertTrue(
            "the migration must add the nullable atMs column: ${ScheduledTaskSchemaSql.ADD_AT_MS_COLUMN}",
            ScheduledTaskSchemaSql.ADD_AT_MS_COLUMN.endsWith("ADD COLUMN atMs INTEGER")
        )
        assertTrue(
            "Room must generate the same atMs column the migration adds: $createTable",
            createTable!!.contains("`atMs` INTEGER")
        )
        assertFalse(
            "the one-shot column must stay nullable (existing rows keep NULL): $createTable",
            createTable.contains("`atMs` INTEGER NOT NULL")
        )
    }

    @Test
    fun migrationIsANonDestructiveAddColumn() {
        val statement = ScheduledTaskSchemaSql.ADD_AT_MS_COLUMN
        assertTrue(
            "not a non-destructive ALTER TABLE ... ADD COLUMN: $statement",
            statement.startsWith("ALTER TABLE `scheduled_tasks`") ||
                statement.startsWith("ALTER TABLE scheduled_tasks")
        )
        assertTrue("must add a column: $statement", statement.contains("ADD COLUMN atMs INTEGER"))
        listOf("NOT NULL", "DROP", "DELETE", "DEFAULT").forEach { forbidden ->
            assertFalse("destructive or defaulted DDL is not allowed: $statement", statement.contains(forbidden))
        }
    }

    @Test
    fun addedColumnNameIsAtMs() {
        val column = COLUMN_NAME.find(ScheduledTaskSchemaSql.ADD_AT_MS_COLUMN)!!.groupValues[1]
        assertEquals("atMs", column)
    }

    private companion object {
        const val TABLE_MARKER = "`scheduled_tasks`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val COLUMN_NAME = Regex("ADD COLUMN `?([A-Za-z0-9_]+)`?")
    }
}
