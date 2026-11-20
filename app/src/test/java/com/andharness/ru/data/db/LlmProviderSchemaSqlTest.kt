package com.andharness.ru.data.db

import java.io.File
import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM guard for the saved-provider table added in migration v41 -> v42, mirroring
 * [CommunicationsMigrationSqlTest]:
 * - the DDL must match what Room generates from `LlmProviderEntity`, otherwise schema validation
 *   fails at runtime;
 * - only a new table is created, and the sibling `ALTER TABLE settings ADD COLUMN ...` is nullable,
 *   so the migration is non-destructive by construction
 *   (https://developer.android.com/training/data-storage/room/migrating-db-versions);
 * - the entity, DAO and migration are actually registered.
 */
class LlmProviderSchemaSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain `llm_providers` yet (stale build); skipped",
            sql.contains("`llm_providers`")
        )

        val expected = LlmProviderSchemaSql.all
            .filter { it.startsWith("CREATE") }
            .toSet()
        val actual = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            .filter { it.startsWith("CREATE") }
            .filter { it.contains("`llm_providers`") }
            .toSet()

        assertEquals(
            "migration SQL must match the schema Room generates (missing or extra statements)",
            expected,
            actual
        )
    }

    @Test
    fun everyStatementIsNonDestructive() {
        LlmProviderSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                NON_DESTRUCTIVE_PREFIXES.any { statement.startsWith(it) }
            )
        }
    }

    @Test
    fun tableHasEveryProfileColumnAndAutoId() {
        val table = LlmProviderSchemaSql.all.single()
        listOf(
            "`id`", "`name`", "`endpoint`", "`model`",
            "`authStyle`", "`authHeaderName`", "`authQueryParam`", "`createdAt`",
        ).forEach { column ->
            assertTrue("llm_providers is missing $column: $table", table.contains(column))
        }
        assertTrue(
            "id must be an auto-generated primary key",
            table.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")
        )
    }

    @Test
    fun appDatabaseIsVersion42AndListsTheProviderEntity() {
        val source = locate(APP_DATABASE_CANDIDATES)
        assumeTrue("AppDatabase.kt not found; source check skipped", source != null)
        val text = source!!.readText()
        assertTrue("AppDatabase must be version 42", text.contains("version = 42"))
        assertTrue(
            "AppDatabase.entities is missing LlmProviderEntity",
            text.contains("LlmProviderEntity::class")
        )
        assertTrue(
            "AppDatabase must expose llmProviderDao()",
            text.contains("llmProviderDao")
        )
    }

    @Test
    fun migration41To42IsRegisteredAndAddsANullableActiveId() {
        val source = locate(MIGRATIONS_LATE_CANDIDATES)
        assumeTrue("DbMigrationsLate.kt not found; registration check skipped", source != null)
        val text = source!!.readText()
        assertTrue("migration must create the provider table", text.contains("LlmProviderSchemaSql"))
        assertTrue("MIGRATION_41_42 must be defined", text.contains("MIGRATION_41_42"))
        assertTrue(
            "MIGRATION_41_42 must be part of the ALL array",
            text.contains("MIGRATION_40_41,\n        MIGRATION_41_42")
        )
        assertTrue(
            "settings.activeProviderId must be added as a nullable INTEGER column",
            text.contains("ALTER TABLE settings ADD COLUMN activeProviderId INTEGER")
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
        )

        val EXEC_SQL = Regex("execSQL\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)")

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
