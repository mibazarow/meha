package com.andharness.ru.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.andharness.ru.testutil.GeneratedImplLocator

/**
 * Pure JVM cross-check of the token-ledger migration against the schema Room itself generates
 * (update.md «Идеи следующих волн», idea #2), mirroring [ToolAuditMigrationSqlTest]: it compares
 * [LlmUsageSchemaSql] with the kapt-generated `AppDatabase_Impl.java`, so a typo or a missing index
 * is caught without a device. Skipped (never failed) when the generated file is unavailable.
 */
class LlmUsageMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain llm_usage yet (stale build); skipped",
            sql.contains(TABLE_MARKER)
        )

        val expected = LlmUsageSchemaSql.all.toSet()
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
    fun everyMigrationStatementIsANonDestructiveCreate() {
        LlmUsageSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive CREATE: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    statement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")
            )
        }
    }

    @Test
    fun atIndexFollowsRoomNamingConvention() {
        val indexStatement = LlmUsageSchemaSql.all.single { it.contains("INDEX") }
        assertEquals("index_llm_usage_at", INDEX_NAME.find(indexStatement)!!.groupValues[1])
    }

    @Test
    fun tokenColumnsAreNullableSoUnknownStaysUnknown() {
        val table = LlmUsageSchemaSql.CREATE_LLM_USAGE_TABLE
        listOf("`promptTokens` INTEGER", "`completionTokens` INTEGER", "`totalTokens` INTEGER")
            .forEach { column ->
                assertTrue("$column must be nullable: $table", table.contains("$column, ") || table.contains("$column)"))
                assertTrue(
                    "$column must not be NOT NULL: $table",
                    !table.contains("$column NOT NULL")
                )
            }
        listOf("`at`", "`chatId`", "`model`", "`source`", "`routed`").forEach { column ->
            assertTrue("llm_usage is missing $column: $table", table.contains(column))
        }
    }

    private companion object {
        const val TABLE_MARKER = "`llm_usage`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
