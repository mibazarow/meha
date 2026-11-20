package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the generic entity graph migration against the schema Room itself
 * generates, mirroring [MemoryPersonMigrationSqlTest]: it compares [MemoryEntitySchemaSql] with the
 * kapt-generated `AppDatabase_Impl.java`, so a typo or a missing index is caught without a device.
 * Skipped (never failed) when the generated file is unavailable.
 */
class MemoryEntityMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain the entity graph yet (stale build); skipped",
            sql.contains(ENTITIES_MARKER) && sql.contains(MEMORY_ENTITIES_MARKER)
        )

        val expected = MemoryEntitySchemaSql.all
            .filter { it.startsWith("CREATE") }
            .toSet()
        val actual = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            .filter { it.startsWith("CREATE") }
            .filter { it.contains(ENTITIES_MARKER) || it.contains(MEMORY_ENTITIES_MARKER) }
            .toSet()

        assertEquals(
            "migration SQL must match the schema Room generates (missing or extra statements)",
            expected,
            actual
        )
    }

    @Test
    fun everyMigrationStatementIsNonDestructive() {
        MemoryEntitySchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    statement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")
            )
        }
    }

    @Test
    fun indexNamesFollowRoomNamingConvention() {
        val uniqueIndex = MemoryEntitySchemaSql.CREATE_ENTITIES_NORMALIZED_NAME_UNIQUE_INDEX
        assertEquals(
            "index_entities_normalizedName",
            INDEX_NAME.find(uniqueIndex)!!.groupValues[1]
        )
        val entityIndex = MemoryEntitySchemaSql.CREATE_MEMORY_ENTITIES_ENTITY_INDEX
        assertEquals(
            "index_memory_entities_entityId",
            INDEX_NAME.find(entityIndex)!!.groupValues[1]
        )
    }

    @Test
    fun tablesCarryEveryColumn() {
        val nodes = MemoryEntitySchemaSql.CREATE_ENTITIES_TABLE
        listOf("`id`", "`name`", "`normalizedName`", "`kind`", "`createdAt`", "`lastSeenAt`")
            .forEach { column ->
                assertTrue("entities is missing $column: $nodes", nodes.contains(column))
            }
        assertTrue(
            "id must be the autoincrement primary key",
            nodes.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")
        )

        val join = MemoryEntitySchemaSql.CREATE_MEMORY_ENTITIES_TABLE
        listOf("`noteId`", "`entityId`", "`weight`", "`createdAt`").forEach { column ->
            assertTrue("memory_entities is missing $column: $join", join.contains(column))
        }
        assertTrue(
            "noteId+entityId must be the composite primary key",
            join.contains("PRIMARY KEY(`noteId`, `entityId`)")
        )
        assertTrue("weight must be REAL", join.contains("`weight` REAL NOT NULL"))
    }

    private companion object {
        const val ENTITIES_MARKER = "`entities`"
        const val MEMORY_ENTITIES_MARKER = "`memory_entities`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
