package com.andharness.ru.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.andharness.ru.testutil.GeneratedImplLocator

/**
 * Pure JVM cross-check of the SSH-registry migration against the schema Room itself expects
 * (update.md §5.2, §6 step 6).
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
class SshMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain ssh_servers yet (stale build); skipped",
            sql.contains(SSH_TABLE_MARKER)
        )

        val expected = SshSchemaSql.all.toSet()
        val actual = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            // `dropAllTables()` also mentions the table; only the CREATE statements are the schema.
            .filter { it.startsWith("CREATE") }
            .filter { it.contains(SSH_TABLE_MARKER) }
            .toSet()

        assertEquals(
            "migration SQL must match the schema Room generates (missing or extra statements)",
            expected,
            actual
        )
    }

    @Test
    fun everyMigrationStatementIsANonDestructiveCreate() {
        SshSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive CREATE: $statement",
                statement.startsWith("CREATE TABLE IF NOT EXISTS") ||
                    statement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS") ||
                    statement.startsWith("CREATE INDEX IF NOT EXISTS")
            )
        }
    }

    @Test
    fun nameIndexIsUniqueAndFollowsRoomNamingConvention() {
        val indexStatement = SshSchemaSql.all.single { it.contains("INDEX") }
        assertTrue(
            "the server name must be unique: $indexStatement",
            indexStatement.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")
        )
        assertEquals(
            "index_ssh_servers_name",
            INDEX_NAME.find(indexStatement)!!.groupValues[1]
        )
    }

    @Test
    fun theMigrationCarriesNoSecretColumn() {
        // update.md §5.4: Room must not hold passwords, passphrases or key material. Only the SAF
        // URI of a key may be referenced.
        val forbidden = listOf("password", "passphrase", "privateKey", "keyMaterial", "secret")
        val table = SshSchemaSql.CREATE_SSH_SERVERS_TABLE
        forbidden.forEach { needle ->
            assertTrue(
                "the ssh_servers table must not contain a '$needle' column: $table",
                !table.contains("`$needle`", ignoreCase = true)
            )
        }
        assertTrue("the SAF key reference must be present", table.contains("`keyRef`"))
    }

    private companion object {
        const val SSH_TABLE_MARKER = "`ssh_servers`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex("INDEX IF NOT EXISTS `([^`]+)`")
    }
}
