package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the semantic-recall migration against the schema Room
 * itself generates, mirroring [RagChunkMigrationSqlTest]: it compares [MemoryVectorSchemaSql] with
 * the kapt-generated `AppDatabase_Impl.java`, so a typo or a missing index is caught without a
 * device. Skipped (never failed) when the generated file is unavailable.
 */
class MemoryVectorMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain memory_vectors yet (stale build); skipped",
            sql.contains(TABLE_MARKER)
        )

        val expected = MemoryVectorSchemaSql.all
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
        MemoryVectorSchemaSql.all.forEach { statement ->
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
    fun memoryIdIndexFollowsRoomNamingConvention() {
        val indexStatement = MemoryVectorSchemaSql.all.single { it.contains("INDEX") }
        assertEquals("index_memory_vectors_memoryId", INDEX_NAME.find(indexStatement)!!.groupValues[1])
    }

    @Test
    fun tableCarriesEveryColumn() {
        val table = MemoryVectorSchemaSql.CREATE_MEMORY_VECTORS_TABLE
        listOf("`memoryId`", "`dim`", "`vector`", "`model`", "`updatedAt`").forEach { column ->
            assertTrue("memory_vectors is missing $column: $table", table.contains(column))
        }
        assertTrue("memoryId must be the primary key", table.contains("PRIMARY KEY(`memoryId`)"))
        assertTrue("vector must be a BLOB", table.contains("`vector` BLOB NOT NULL"))
    }

    @Test
    fun settingsColumnIsAddedWithAnEmptyDefault() {
        assertTrue(
            "a blank default is what keeps semantic search off after migration",
            MemoryVectorSchemaSql.ADD_SETTINGS_EMBEDDING_MODEL_COLUMN.contains("DEFAULT ''")
        )
    }

    private companion object {
        const val TABLE_MARKER = "`memory_vectors`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
