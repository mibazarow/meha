package com.andharness.ru.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM guard for the "Communications" raw SQL: it does not need a device or
 * `AppDatabase` (the entities are not wired into the database in this step), it just checks the
 * properties the future migration 35 -> 36 must have.
 *
 * Mirrors the style of [MemoryVectorMigrationSqlTest]: allowed statements are asserted against the
 * same non-destructive whitelist and index names against Room's `index_<table>_<col>...` convention
 * (https://developer.android.com/training/data-storage/room/defining-data#indices).
 */
class CommunicationsSchemaSqlTest {

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
    fun transcriptVectorsIsKeyedBySegment() {
        val create = allStatements().single { it.startsWith("CREATE TABLE IF NOT EXISTS `transcript_vectors`") }
        assertTrue(
            "transcript_vectors must be keyed by segmentId (one vector per segment)",
            create.contains("PRIMARY KEY(`segmentId`)")
        )
        assertTrue("vector must be a BLOB", create.contains("`vector` BLOB NOT NULL"))
    }

    @Test
    fun transcriptFtsIsContentBackedAndRebuildable() {
        assertTrue(
            "transcript_fts must be a unicode61 FTS4 table over transcript_segments",
            TranscriptFtsSchemaSql.CREATE_TRANSCRIPT_FTS_TABLE.contains(
                "tokenize=unicode61, content=`transcript_segments`"
            )
        )
        assertEquals(
            "rebuild is a data statement, not part of the DDL list",
            0,
            TranscriptFtsSchemaSql.all.count { it == TranscriptFtsSchemaSql.REBUILD_INDEX }
        )
    }

    private fun allStatements(): List<String> =
        CommunicationsSchemaSql.all + TranscriptFtsSchemaSql.all

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
            "recordings" to listOf(
                "id", "sourceType", "filePath", "displayName", "durationMs", "sizeBytes",
                "sourcePackage", "transcriptStatus", "keepFlag", "personId", "errorText",
                "createdAt", "updatedAt"
            ),
            "transcript_segments" to listOf(
                "id", "recordingId", "startMs", "endMs", "text", "speakerId", "confidence", "createdAt"
            ),
            "transcript_vectors" to listOf("segmentId", "dim", "vector", "model", "updatedAt"),
            "speakers" to listOf(
                "id", "name", "isSelf", "personId", "voiceModel", "voiceDim", "centroid",
                "sampleCount", "confidence", "createdAt", "updatedAt"
            ),
            "speaker_voice_embeddings" to listOf(
                "id", "speakerId", "recordingId", "dim", "vector", "model", "quality", "createdAt"
            ),
        )
    }
}
