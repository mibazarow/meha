package com.andharness.ru.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.andharness.ru.testutil.GeneratedImplLocator

/**
 * Pure JVM cross-check of the tool-audit migration against the schema Room itself generates
 * (update.md «Идеи следующих волн», idea #6), mirroring [SshMigrationSqlTest]: it compares
 * [ToolAuditSchemaSql] with the kapt-generated `AppDatabase_Impl.java`, so a typo or a missing
 * index is caught without a device. Skipped (never failed) when the generated file is unavailable.
 */
class ToolAuditMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain tool_audit yet (stale build); skipped",
            sql.contains(TABLE_MARKER)
        )

        val expected = ToolAuditSchemaSql.all.toSet()
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
        ToolAuditSchemaSql.all.forEach { statement ->
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
        val indexStatement = ToolAuditSchemaSql.all.single { it.contains("INDEX") }
        assertEquals("index_tool_audit_at", INDEX_NAME.find(indexStatement)!!.groupValues[1])
    }

    @Test
    fun tableCarriesEveryHashChainColumn() {
        val table = ToolAuditSchemaSql.CREATE_TOOL_AUDIT_TABLE
        listOf(
            "`at`", "`chatId`", "`tool`", "`argsPreview`", "`success`",
            "`resultHash`", "`durationMs`", "`model`", "`prevHash`", "`hash`"
        ).forEach { column ->
            assertTrue("tool_audit is missing $column: $table", table.contains(column))
        }
    }

    private companion object {
        const val TABLE_MARKER = "`tool_audit`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
