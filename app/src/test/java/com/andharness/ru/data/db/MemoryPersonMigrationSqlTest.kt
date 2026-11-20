package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the memory-person link migration against the schema Room
 * itself generates, mirroring [MemoryVectorMigrationSqlTest]: it compares [MemoryPersonSchemaSql]
 * with the kapt-generated `AppDatabase_Impl.java`, so a typo or a missing index is caught without a
 * device. Skipped (never failed) when the generated file is unavailable.
 */
class MemoryPersonMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain memory_persons yet (stale build); skipped",
            sql.contains(TABLE_MARKER)
        )

        val expected = MemoryPersonSchemaSql.all
            .filter { it.startsWith("CREATE") }
            .toSet()
        val actual = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            .filter { it.startsWith("CREATE") }
            .filter { it.contains(TABLE_MARKER) }
            .toSet()

        assertEquals(
            "migration SQL must match the schema Room generates (missing or extra statements)",
            expected,
            actual
        )
    }

    @Test
    fun everyMigrationStatementIsNonDestructive() {
        MemoryPersonSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    statement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS") ||
                    statement.startsWith("ALTER TABLE settings ADD COLUMN")
            )
        }
    }

    @Test
    fun personIndexFollowsRoomNamingConvention() {
        val indexStatement = MemoryPersonSchemaSql.all.single { it.contains("INDEX") }
        assertEquals(
            "index_memory_persons_personId",
            INDEX_NAME.find(indexStatement)!!.groupValues[1]
        )
    }

    @Test
    fun tableCarriesEveryColumn() {
        val table = MemoryPersonSchemaSql.CREATE_MEMORY_PERSONS_TABLE
        listOf("`noteId`", "`personId`", "`weight`", "`createdAt`").forEach { column ->
            assertTrue("memory_persons is missing $column: $table", table.contains(column))
        }
        assertTrue(
            "noteId+personId must be the composite primary key",
            table.contains("PRIMARY KEY(`noteId`, `personId`)")
        )
        assertTrue("weight must be REAL", table.contains("`weight` REAL NOT NULL"))
    }

    private companion object {
        const val TABLE_MARKER = "`memory_persons`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
