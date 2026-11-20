package com.andharness.ru.data.db.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM checks of the read-only viewer's allow-list and formatting rules (update.md
 * «Запланировано» → «Просмотр базы данных»). The allow-list is the security boundary that keeps a
 * caller from selecting an arbitrary table, so it is asserted explicitly.
 */
class DatabaseViewerSpecTest {

    private val expectedTables = setOf(
        "chats",
        "messages",
        "settings",
        "scheduled_tasks",
        "notes",
        "todos",
        "saf_grants",
        "trash_entries",
        "folder_watches",
        "folder_watch_events",
        "device_facts",
        "device_fact_events",
        "exact_alarms",
        "automation_rules",
        "automation_events",
        "crash_logs",
        "restart_state",
        "persons",
        "person_identities",
        "interactions",
        "person_aliases",
        "ssh_servers",
        "tool_audit",
        "llm_usage",
        "rag_chunks",
        "recordings",
        "transcript_segments",
        "transcript_vectors",
        "speakers",
        "speaker_voice_embeddings"
    )

    @Test
    fun allowListMatchesEveryRoomTable() {
        assertEquals(expectedTables, DatabaseViewerSpec.allTables)
    }

    @Test
    fun sectionsCoverEveryTableExactlyOnce() {
        val listed = DatabaseViewerSpec.sections.flatMap { section -> section.tables.map { it.table } }
        assertEquals("no table may be listed twice or omitted", expectedTables.size, listed.size)
        assertEquals(expectedTables, listed.toSet())
    }

    @Test
    fun everyTableHasANonBlankHumanLabel() {
        DatabaseViewerSpec.sections.forEach { section ->
            assertTrue(section.title.isNotBlank())
            section.tables.forEach { ref ->
                assertTrue("blank label for ${ref.table}", ref.label.isNotBlank())
                assertFalse("label must not be the raw table name: ${ref.table}", ref.label == ref.table)
            }
        }
    }

    @Test
    fun remainingSectionHoldsTheUncuratedTables() {
        val remaining = DatabaseViewerSpec.sections.single { it.title == "Остальные таблицы" }
        assertEquals(
            setOf("person_aliases", "settings", "folder_watch_events", "restart_state", "llm_usage"),
            remaining.tables.map { it.table }.toSet()
        )
    }

    @Test
    fun onlyAllowListedTablesAreAccepted() {
        expectedTables.forEach { table ->
            assertTrue("$table must be allowed", DatabaseViewerSpec.isAllowed(table))
        }
        listOf("persons; DROP TABLE persons", "sqlite_master", "room_master_table", "", "persons2")
            .forEach { hostile ->
                assertFalse("$hostile must be rejected", DatabaseViewerSpec.isAllowed(hostile))
            }
    }

    @Test
    fun bigTablesAreMarkedForPagination() {
        assertEquals(
            setOf(
                "interactions", "messages", "device_fact_events", "tool_audit",
                "rag_chunks", "automation_events", "transcript_segments", "transcript_vectors",
                "speaker_voice_embeddings"
            ),
            DatabaseViewerSpec.allTables.filter(DatabaseViewerSpec::isBig).toSet()
        )
    }

    @Test
    fun timestampColumnsAreDetectedButPlainNumbersAreNot() {
        listOf("at", "createdAt", "updatedAt", "addedAtMs", "timeMs", "started_at", "lastSeenAt")
            .forEach { assertTrue("$it should be a timestamp column", DatabaseViewerSpec.isTimestampColumn(it)) }
        listOf("id", "state", "format", "summary", "owner", "count", "enabled")
            .forEach { assertFalse("$it should not be a timestamp column", DatabaseViewerSpec.isTimestampColumn(it)) }
    }

    @Test
    fun epochMillisWindowRejectsSmallCounters() {
        assertTrue(DatabaseViewerSpec.looksLikeEpochMillis(1_700_000_000_000L))
        assertFalse(DatabaseViewerSpec.looksLikeEpochMillis(42L))
        assertFalse(DatabaseViewerSpec.looksLikeEpochMillis(0L))
    }

    @Test
    fun integersAreFormattedOnlyWhenTheyAreTimestamps() {
        val formatted = DatabaseViewerSpec.formatInteger("createdAt", 1_700_000_000_000L)
        assertFalse("a real timestamp must not be shown as raw millis", formatted == "1700000000000")
        assertTrue(formatted.contains("."))

        assertEquals("7", DatabaseViewerSpec.formatInteger("count", 7L))
        assertEquals("1700000000", DatabaseViewerSpec.formatInteger("createdAt", 1_700_000_000L))
    }

    @Test
    fun longTextCellsAreElided() {
        val long = "x".repeat(1000)
        val trimmed = DatabaseViewerSpec.truncate(long)
        assertEquals(601, trimmed.length)
        assertTrue(trimmed.endsWith("…"))
        assertEquals("short", DatabaseViewerSpec.truncate("short"))
    }

    @Test
    fun pageSizeIsBounded() {
        assertTrue(DatabaseViewerSpec.PAGE_SIZE in 10..500)
    }
}
