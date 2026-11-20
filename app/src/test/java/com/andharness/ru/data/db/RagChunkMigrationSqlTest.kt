package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the RAG index migration against the schema Room itself generates
 * (update.md idea #4), mirroring [ToolAuditMigrationSqlTest]: it compares [RagChunkSchemaSql] with
 * the kapt-generated `AppDatabase_Impl.java`, so a typo or a missing index is caught without a
 * device. Skipped (never failed) when the generated file is unavailable.
 */
class RagChunkMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain rag_chunks yet (stale build); skipped",
            sql.contains(TABLE_MARKER)
        )

        val expected = RagChunkSchemaSql.all.toSet()
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
        RagChunkSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive CREATE: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    statement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")
            )
        }
    }

    @Test
    fun sourcePathIndexFollowsRoomNamingConvention() {
        val indexStatement = RagChunkSchemaSql.all.single { it.contains("INDEX") }
        assertEquals("index_rag_chunks_sourcePath", INDEX_NAME.find(indexStatement)!!.groupValues[1])
    }

    @Test
    fun tableCarriesEveryIndexColumn() {
        val table = RagChunkSchemaSql.CREATE_RAG_CHUNKS_TABLE
        listOf(
            "`sourcePath`", "`sourceType`", "`chunkIndex`", "`text`", "`updatedAt`", "`sizeBytes`"
        ).forEach { column ->
            assertTrue("rag_chunks is missing $column: $table", table.contains(column))
        }
    }

    private companion object {
        const val TABLE_MARKER = "`rag_chunks`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
