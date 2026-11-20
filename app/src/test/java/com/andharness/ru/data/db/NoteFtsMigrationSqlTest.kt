package com.andharness.ru.data.db

import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Чистая JVM-сверка миграции FTS-индекса памяти со схемой, которую Room сам
 * генерирует в `AppDatabase_Impl`, по образцу [RagChunkMigrationSqlTest]: опечатка или
 * расхождение в триггерах/опциях ловится без устройства. Пропускается (не падает), если
 * сгенерированный файл недоступен.
 */
class NoteFtsMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain notes_fts yet (stale build); skipped",
            sql.contains(TABLE_MARKER)
        )

        val expected = NoteFtsSchemaSql.all.toSet()
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
    fun virtualTableIndexesTextAndTagsWithUnicodeTokenizer() {
        val table = NoteFtsSchemaSql.CREATE_NOTES_FTS_TABLE
        listOf("`text`", "`tags`", "tokenize=unicode61", "content=`notes`").forEach { part ->
            assertTrue("notes_fts is missing $part: $table", table.contains(part))
        }
        assertTrue(table.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS"))
    }

    @Test
    fun contentSyncTriggersCoverBothUpdateAndDeleteAndInsert() {
        val all = NoteFtsSchemaSql.all.joinToString("\n")
        listOf(
            "room_fts_content_sync_notes_fts_BEFORE_UPDATE",
            "room_fts_content_sync_notes_fts_BEFORE_DELETE",
            "room_fts_content_sync_notes_fts_AFTER_UPDATE",
            "room_fts_content_sync_notes_fts_AFTER_INSERT"
        ).forEach { trigger ->
            assertTrue("missing trigger $trigger", all.contains(trigger))
        }
    }

    @Test
    fun backfillRebuildsTheIndexWithoutTouchingNotes() {
        assertTrue(NoteFtsSchemaSql.REBUILD_INDEX.startsWith("INSERT INTO `notes_fts`"))
        assertTrue(NoteFtsSchemaSql.REBUILD_INDEX.contains("'rebuild'"))
        assertTrue(
            "every DDL statement is a non-destructive CREATE",
            NoteFtsSchemaSql.all.all { it.startsWith("CREATE VIRTUAL TABLE IF NOT EXISTS") || it.startsWith("CREATE TRIGGER IF NOT EXISTS") }
        )
    }

    private companion object {
        const val TABLE_MARKER = "`notes_fts`"

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
    }
}
