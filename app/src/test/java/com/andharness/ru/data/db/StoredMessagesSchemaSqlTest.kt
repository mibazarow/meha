package com.andharness.ru.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM guard for the full-text messages store raw SQL: it needs no device or `AppDatabase`, just
 * checks the properties the migration 37 -> 38 must have. Mirrors [CommunicationsSchemaSqlTest]:
 * allowed statements are asserted against the same non-destructive whitelist and index names against
 * Room's `index_<table>_<col>...` convention
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
class StoredMessagesSchemaSqlTest {

    @Test
    fun everyStatementIsNonDestructive() {
        allStatements().forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                NON_DESTRUCTIVE_PREFIXES.any { statement.startsWith(it) }
            )
        }
    }

    @Test
    fun everyCreateUsesIfNotExists() {
        allStatements()
            .filter { it.startsWith("CREATE") }
            .forEach { statement ->
                assertTrue("CREATE without `IF NOT EXISTS`: $statement", statement.contains("IF NOT EXISTS"))
            }
    }

    @Test
    fun indexNamesFollowRoomConvention() {
        val indexStatements = allStatements().filter { it.contains("INDEX IF NOT EXISTS") }
        assertTrue("expected at least one index statement", indexStatements.isNotEmpty())
        indexStatements.forEach { statement ->
            val match = INDEX_NAME.find(statement)
                ?: throw AssertionError("unparsable index statement: $statement")
            val name = match.groups["name"]!!.value
            val table = match.groups["table"]!!.value
            val columns = COLUMN.findAll(match.groups["cols"]!!.value).map { it.groupValues[1] }.toList()
            assertEquals(
                "index name must be index_<table>_<col>[_<col>...]: $statement",
                "index_${table}_${columns.joinToString("_")}",
                name
            )
        }
    }

    @Test
    fun tablesCarryExpectedColumns() {
        EXPECTED_COLUMNS.forEach { (table, columns) ->
            val create = allStatements().single {
                it.startsWith("CREATE TABLE IF NOT EXISTS `$table`")
            }
            columns.forEach { column ->
                assertTrue("`$table` is missing column `$column`: $create", create.contains("`$column`"))
            }
        }
    }

    @Test
    fun externalRefCarriesAUniqueDedupIndex() {
        val index = StoredMessageSchemaSql.CREATE_STORED_MESSAGES_EXTERNAL_REF_UNIQUE_INDEX
        assertTrue(
            "externalRef needs a UNIQUE index so a source event is never stored twice",
            index.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_stored_messages_externalRef`")
        )
        assertTrue(index.contains("ON `stored_messages` (`externalRef`)"))
    }

    @Test
    fun messageVectorsIsKeyedByMessage() {
        val create = allStatements()
            .single { it.startsWith("CREATE TABLE IF NOT EXISTS `stored_message_vectors`") }
        assertTrue(
            "stored_message_vectors must be keyed by messageId (one vector per message)",
            create.contains("PRIMARY KEY(`messageId`)")
        )
        assertTrue("vector must be a BLOB", create.contains("`vector` BLOB NOT NULL"))
    }

    @Test
    fun storedMessageFtsIsContentBackedAndRebuildable() {
        assertTrue(
            "stored_message_fts must be a unicode61 FTS4 table over stored_messages",
            StoredMessageFtsSchemaSql.CREATE_STORED_MESSAGE_FTS_TABLE.contains(
                "tokenize=unicode61, content=`stored_messages`"
            )
        )
        assertEquals(
            "rebuild is a data statement, not part of the DDL list",
            0,
            StoredMessageFtsSchemaSql.all.count { it == StoredMessageFtsSchemaSql.REBUILD_INDEX }
        )
    }

    private fun allStatements(): List<String> =
        StoredMessageSchemaSql.all + StoredMessageFtsSchemaSql.all

    private companion object {
        val NON_DESTRUCTIVE_PREFIXES = listOf(
            "CREATE TABLE IF NOT EXISTS",
            "CREATE VIRTUAL TABLE IF NOT EXISTS",
            "CREATE INDEX IF NOT EXISTS",
            "CREATE UNIQUE INDEX IF NOT EXISTS",
            "CREATE TRIGGER IF NOT EXISTS",
        )

        val INDEX_NAME = Regex(
            "CREATE (?:UNIQUE )?INDEX IF NOT EXISTS `(?<name>[^`]+)` " +
                "ON `(?<table>[^`]+)` \\((?<cols>[^)]*)\\)"
        )
        val COLUMN = Regex("`([^`]+)`")

        val EXPECTED_COLUMNS = mapOf(
            "stored_messages" to listOf(
                "id", "sourceType", "packageName", "conversationKey", "personId", "direction",
                "sender", "isGroup", "at", "text", "externalRef", "createdAt"
            ),
            "stored_message_vectors" to listOf("messageId", "dim", "vector", "model", "updatedAt"),
        )
    }
}
