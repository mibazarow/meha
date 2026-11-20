package com.andharness.ru.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.andharness.ru.testutil.GeneratedImplLocator

/**
 * Pure JVM cross-check of the unified-personal-base migrations against the schema Room itself
 * expects (update.md §4.3).
 *
 * The expected schema is taken from the kapt-generated
 * `app/build/generated/source/kapt/debug/com/andharness/ru/data/db/AppDatabase_Impl.java`
 * (`createAllTables()`), which is produced before the unit tests run. Comparing the migration SQL to
 * it catches a typo or a forgotten index **without** a device — at runtime Room would reject the
 * migrated database anyway
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
 *
 * If the generated file is unavailable or stale the test is skipped rather than failed, so it can
 * never produce a false red on a clean checkout.
 */
class PersonMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain the persons tables yet (stale build); skipped",
            PERSON_TABLE_MARKERS.any { sql.contains(it) }
        )

        val expected = PersonSchemaSql.all.toSet()
        val actual = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            // `dropAllTables()` / `clearAllTables()` also mention these tables; only the schema
            // definition (CREATE) statements are what the migrations must reproduce.
            .filter { it.startsWith("CREATE") }
            .filter { statement -> PERSON_TABLE_MARKERS.any { statement.contains(it) } }
            .toSet()

        assertEquals(
            "migration SQL must match the schema Room generates (missing or extra statements)",
            expected,
            actual
        )
    }

    @Test
    fun everyMigrationsStatementIsANonDestructiveCreate() {
        PersonSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive CREATE: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    statement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS")
            )
        }
    }

    @Test
    fun indexNamesFollowRoomNamingConvention() {
        val actualIndexNames = PersonSchemaSql.all
            .filter { it.contains("INDEX") }
            .map { INDEX_NAME.find(it)!!.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "index_persons_displayName",
                "index_person_identities_type_value_appPackage",
                "index_person_identities_personId",
                "index_interactions_personId_at",
                "index_interactions_at",
                "index_person_aliases_personId_alias",
                "index_person_aliases_alias"
            ),
            actualIndexNames
        )
    }

    @Test
    fun onlyTheTwoIdentityAndAliasIndicesAreUnique() {
        val uniqueIndexNames = PersonSchemaSql.all
            .filter { it.startsWith("CREATE UNIQUE INDEX") }
            .map { INDEX_NAME.find(it)!!.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "index_person_identities_type_value_appPackage",
                "index_person_aliases_personId_alias"
            ),
            uniqueIndexNames
        )
    }

    private companion object {
        /** Backtick-quoted table names, matched so that `persons` does not match `person_identities`. */
        val PERSON_TABLE_MARKERS = listOf(
            "`persons`",
            "`person_identities`",
            "`interactions`",
            "`person_aliases`"
        )

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
