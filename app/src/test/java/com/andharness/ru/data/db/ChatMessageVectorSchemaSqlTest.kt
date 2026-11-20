package com.andharness.ru.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM guard for the chat-message embedding store raw SQL and its migration registration. Needs
 * no device or `AppDatabase`: it checks the properties the migration 38 -> 39 must have, mirroring
 * [MemoryVectorMigrationSqlTest] / [StoredMessagesSchemaSqlTest].
 */
class ChatMessageVectorSchemaSqlTest {

    @Test
    fun everyStatementIsNonDestructive() {
        ChatMessageVectorSchemaSql.all.forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                NON_DESTRUCTIVE_PREFIXES.any { statement.startsWith(it) }
            )
        }
    }

    @Test
    fun everyCreateUsesIfNotExists() {
        ChatMessageVectorSchemaSql.all
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
        val indexStatement = ChatMessageVectorSchemaSql.all.single { it.contains("INDEX IF NOT EXISTS") }
        assertEquals(
            "index_chat_message_vectors_model",
            INDEX_NAME.find(indexStatement)!!.groupValues[1]
        )
    }

    @Test
    fun tableIsKeyedByMessageIdAndStoresADimTaggedBlobVector() {
        val table = ChatMessageVectorSchemaSql.CREATE_CHAT_MESSAGE_VECTORS_TABLE
        listOf("`messageId`", "`dim`", "`vector`", "`model`", "`updatedAt`").forEach { column ->
            assertTrue("chat_message_vectors is missing $column: $table", table.contains(column))
        }
        assertTrue(
            "messageId must be the primary key (one vector per message)",
            table.contains("PRIMARY KEY(`messageId`)")
        )
        assertTrue("vector must be a BLOB", table.contains("`vector` BLOB NOT NULL"))
    }

    @Test
    fun appDatabaseIsVersion42AndListsTheNewIndex() {
        val source = locate(APP_DATABASE_CANDIDATES)
        assumeTrue("AppDatabase.kt not found; source check skipped", source != null)
        val text = source!!.readText()
        assertTrue("AppDatabase must be version 42", text.contains("version = 42"))
        assertTrue(
            "AppDatabase.entities is missing ChatMessageVectorEntity",
            text.contains("ChatMessageVectorEntity::class")
        )
        assertTrue(
            "AppDatabase must expose chatMessageVectorDao()",
            text.contains("chatMessageVectorDao")
        )
    }

    @Test
    fun migration38To39IsRegisteredInTheOrderedList() {
        val source = locate(MIGRATIONS_LATE_CANDIDATES)
        assumeTrue("DbMigrationsLate.kt not found; registration check skipped", source != null)
        val text = source!!.readText()
        assertTrue("migration must create the chat vectors table", text.contains("ChatMessageVectorSchemaSql"))
        assertTrue("MIGRATION_38_39 must be defined", text.contains("MIGRATION_38_39"))
        assertTrue(
            "MIGRATION_38_39 must be part of the ALL array",
            text.contains("MIGRATION_37_38,\n        MIGRATION_38_39")
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
