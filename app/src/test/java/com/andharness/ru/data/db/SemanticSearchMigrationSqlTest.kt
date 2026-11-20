package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the on-device semantic-search migration (v29 -> v30) against the schema
 * Room itself generates, mirroring [ScheduledTaskMigrationSqlTest]: the `semanticSearchEnabled`
 * column added by [SemanticSearchSchemaSql.ADD_SEMANTIC_SEARCH_ENABLED_COLUMN] must match the
 * non-null `settings.semanticSearchEnabled` column Room derives from
 * [com.andharness.ru.data.db.entity.SettingsEntity], so a name/type/nullability typo is caught
 * without a device (Room re-validates the migrated schema at runtime anyway:
 * https://developer.android.com/training/data-storage/room/migrating-db-versions). Skipped (never
 * failed) when the kapt-generated file is unavailable.
 */
class SemanticSearchMigrationSqlTest {

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
            "generated schema does not contain settings yet (stale build); skipped",
            createTable != null
        )

        assertTrue(
            "Room must generate the same non-null column the migration adds: $createTable",
            createTable!!.contains("`semanticSearchEnabled` INTEGER NOT NULL")
        )
    }

    @Test
    fun migrationIsANonDestructiveAddColumnWithOffDefault() {
        val statement = SemanticSearchSchemaSql.ADD_SEMANTIC_SEARCH_ENABLED_COLUMN
        assertTrue(
            "must add a column to settings: $statement",
            statement.startsWith("ALTER TABLE settings ADD COLUMN ")
        )
        assertTrue(
            "semantic search must default to OFF for existing rows: $statement",
            statement.contains("ADD COLUMN semanticSearchEnabled INTEGER NOT NULL DEFAULT 0")
        )
        listOf("DROP", "DELETE").forEach { forbidden ->
            assertFalse("destructive DDL is not allowed: $statement", statement.contains(forbidden))
        }
    }

    @Test
    fun addedColumnNameIsSemanticSearchEnabled() {
        val column = COLUMN_NAME.find(SemanticSearchSchemaSql.ADD_SEMANTIC_SEARCH_ENABLED_COLUMN)!!
            .groupValues[1]
        assertEquals("semanticSearchEnabled", column)
    }

    private companion object {
        const val TABLE_MARKER = "`settings`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val COLUMN_NAME = Regex("ADD COLUMN `?([A-Za-z0-9_]+)`?")
    }
}
