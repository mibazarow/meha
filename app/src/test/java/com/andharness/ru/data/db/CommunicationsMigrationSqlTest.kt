package com.andharness.ru.data.db

import java.io.File
import com.andharness.ru.testutil.GeneratedImplLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pure JVM cross-check of the "Communications" migration (35 -> 36) against the schema Room itself
 * generates, mirroring [MemoryVectorMigrationSqlTest]: it compares [CommunicationsSchemaSql] and
 * [TranscriptFtsSchemaSql] with the kapt-generated `AppDatabase_Impl.java`, so a typo or a missing
 * index is caught without a device. Skipped (never failed) when the generated file is unavailable.
 *
 * The migration only creates new tables/indices and the content-backed FTS4 index, so it is
 * non-destructive by construction
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
 */
class CommunicationsMigrationSqlTest {

    @Test
    fun migrationSqlMatchesRoomExpectedSchema() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; schema cross-check skipped",
            generated != null
        )
        val sql = generated!!.readText()
        assumeTrue(
            "generated schema does not contain the communications tables yet (stale build); skipped",
            TABLE_MARKERS.all { sql.contains(it) }
        )

        val expected = allStatements()
            .filter { it.startsWith("CREATE") }
            .toSet()
        val actual = EXEC_SQL.findAll(sql)
            .map { it.groupValues[1] }
            .filter { it.startsWith("CREATE") }
            .filter { statement -> TABLE_MARKERS.any { statement.contains(it) } }
            .toSet()

        assertEquals(
            "migration SQL must match the schema Room generates (missing or extra statements)",
            expected,
            actual
        )
    }

    @Test
    fun everyMigrationStatementIsNonDestructive() {
        allStatements().forEach { statement ->
            assertTrue(
                "not a non-destructive statement: $statement",
                NON_DESTRUCTIVE_PREFIXES.any { statement.startsWith(it) }
            )
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
    fun generatedSchemaUsesVersion42() {
        val generated = GeneratedImplLocator.locate()
        assumeTrue(
            "kapt-generated AppDatabase_Impl not found; version cross-check skipped",
            generated != null
        )
        val match = VERSION.find(generated!!.readText())
            ?: throw AssertionError("RoomOpenHelper.Delegate(<version>) not found in generated schema")
        assertEquals(42, match.groupValues[1].toInt())
    }

    @Test
    fun appDatabaseIsVersion42AndListsAllCommunicationsEntities() {
        val source = locateAppDatabaseSource()
        assumeTrue("AppDatabase.kt not found; source check skipped", source != null)
        val text = source!!.readText()
        assertTrue("AppDatabase must be version 42", text.contains("version = 42"))
        ENTITIES.forEach { entity ->
            assertTrue("AppDatabase.entities is missing $entity", text.contains("$entity::class"))
        }
    }

    private fun allStatements(): List<String> =
        CommunicationsSchemaSql.all + TranscriptFtsSchemaSql.all

    private fun locateAppDatabaseSource(): File? = APP_DATABASE_CANDIDATES
        .map(::File)
        .firstOrNull { it.isFile }

    private companion object {
        const val APP_DATABASE_RELATIVE = "src/main/java/com/andharness/ru/data/db/AppDatabase.kt"

        val APP_DATABASE_CANDIDATES = listOf(
            APP_DATABASE_RELATIVE,
            "app/$APP_DATABASE_RELATIVE"
        )

        val TABLE_MARKERS = listOf(
            "`recordings`",
            "`transcript_segments`",
            "`transcript_vectors`",
            "`speakers`",
            "`speaker_voice_embeddings`",
            "`transcript_fts`"
        )

        val NON_DESTRUCTIVE_PREFIXES = listOf(
            "CREATE TABLE IF NOT EXISTS",
            "CREATE VIRTUAL TABLE IF NOT EXISTS",
            "CREATE INDEX IF NOT EXISTS",
            "CREATE UNIQUE INDEX IF NOT EXISTS",
            "CREATE TRIGGER IF NOT EXISTS",
        )

        val ENTITIES = listOf(
            "RecordingEntity",
            "TranscriptSegmentEntity",
            "TranscriptFtsEntity",
            "TranscriptVectorEntity",
            "SpeakerEntity",
            "SpeakerVoiceEmbeddingEntity"
        )

        val EXEC_SQL = Regex("execSQL\\(\"([^\"]+)\"\\)")
        val INDEX_NAME = Regex(
            "CREATE (?:UNIQUE )?INDEX IF NOT EXISTS `(?<name>[^`]+)` " +
                "ON `(?<table>[^`]+)` \\((?<cols>[^)]*)\\)"
        )
        val COLUMN = Regex("`([^`]+)`")
        val VERSION = Regex("RoomOpenHelper\\.Delegate\\((\\d+)\\)")
    }
}
