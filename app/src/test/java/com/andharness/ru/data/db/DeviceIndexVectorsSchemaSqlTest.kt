package com.andharness.ru.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM guard for the whole-device semantic index raw SQL and its migration registration. Needs no
 * device or `AppDatabase`: it checks the properties the migration 39 -> 40 must have, mirroring
 * [ChatMessageVectorSchemaSqlTest].
 */
class DeviceIndexVectorsSchemaSqlTest {

    @Test
    fun everyStatementIsNonDestructive() {
        DeviceIndexVectorsSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                NON_DESTRUCTIVE_PREFIXES.any { statement.startsWith(it) }
            )
        }
    }

    @Test
    fun everyCreateUsesIfNotExists() {
        DeviceIndexVectorsSchemaSql.all
            .filter { it.startsWith("CREATE") }
            .forEach { statement ->
                assertTrue(
                    "CREATE without `IF NOT EXISTS`: $statement",
                    statement.contains("IF NOT EXISTS")
                )
            }
    }

    @Test
    fun indexNamesFollowRoomConvention() {
        val names = INDEX_NAME.findAll(
            DeviceIndexVectorsSchemaSql.all.joinToString("\n")
        ).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "index_device_index_vectors_sourceUri_chunkIndex",
                "index_device_index_vectors_sourceUri",
                "index_device_index_vectors_kind",
            ),
            names
        )
    }

    @Test
    fun sourceChunkIndexIsUnique() {
        assertTrue(
            "the (sourceUri, chunkIndex) index must be UNIQUE",
            DeviceIndexVectorsSchemaSql.CREATE_DEVICE_INDEX_VECTORS_SOURCE_CHUNK_UNIQUE_INDEX
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS")
        )
    }

    @Test
    fun tableHasTheIndexedUnitColumns() {
        val table = DeviceIndexVectorsSchemaSql.CREATE_DEVICE_INDEX_VECTORS_TABLE
        listOf(
            "`id`", "`kind`", "`sourceUri`", "`displayName`", "`mimeType`", "`sizeBytes`",
            "`lastModified`", "`chunkIndex`", "`text`", "`vector`", "`dim`", "`modelId`", "`updatedAt`",
        ).forEach { column ->
            assertTrue("device_index_vectors is missing $column: $table", table.contains(column))
        }
        assertTrue(
            "id must be an auto-generated primary key",
            table.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")
        )
        assertTrue("vector must be a BLOB", table.contains("`vector` BLOB NOT NULL"))
    }

    @Test
    fun appDatabaseIsVersion42AndListsTheNewEntity() {
        val source = locate(APP_DATABASE_CANDIDATES)
        assumeTrue("AppDatabase.kt not found; source check skipped", source != null)
        val text = source!!.readText()
        assertTrue("AppDatabase must be version 42", text.contains("version = 42"))
        assertTrue(
            "AppDatabase.entities is missing DeviceIndexVectorEntity",
            text.contains("DeviceIndexVectorEntity::class")
        )
        assertTrue(
            "AppDatabase must expose deviceIndexVectorDao()",
            text.contains("deviceIndexVectorDao")
        )
    }

    @Test
    fun migration39To40IsRegisteredInTheOrderedList() {
        val source = locate(MIGRATIONS_LATE_CANDIDATES)
        assumeTrue("DbMigrationsLate.kt not found; registration check skipped", source != null)
        val text = source!!.readText()
        assertTrue("migration must create the device index table", text.contains("DeviceIndexVectorsSchemaSql"))
        assertTrue("MIGRATION_39_40 must be defined", text.contains("MIGRATION_39_40"))
        assertTrue(
            "MIGRATION_39_40 must be part of the ALL array",
            text.contains("MIGRATION_38_39,\n        MIGRATION_39_40")
        )
    }

    private fun locate(candidates: List<String>): File? =
        candidates.map(::File).firstOrNull { it.isFile }

    private companion object {
        val NON_DESTRUCTIVE_PREFIXES = listOf(
            "CREATE TABLE IF NOT EXISTS",
            "CREATE VIRTUAL TABLE IF NOT EXISTS",
            "CREATE INDEX IF NOT EXISTS",
            "CREATE UNIQUE INDEX IF NOT EXISTS",
            "CREATE TRIGGER IF NOT EXISTS",
        )

        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")

        const val DB_RELATIVE = "app/src/main/java/com/andharness/ru/data/db"
        const val MIGRATIONS_RELATIVE = "app/src/main/java/com/andharness/ru/di/migrations"

        val APP_DATABASE_CANDIDATES = listOf(
            "$DB_RELATIVE/AppDatabase.kt",
            "src/main/java/com/andharness/ru/data/db/AppDatabase.kt",
        )
        val MIGRATIONS_LATE_CANDIDATES = listOf(
            "$MIGRATIONS_RELATIVE/DbMigrationsLate.kt",
            "src/main/java/com/andharness/ru/di/migrations/DbMigrationsLate.kt",
        )
    }
}
