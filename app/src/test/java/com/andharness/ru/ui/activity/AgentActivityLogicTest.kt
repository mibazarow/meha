package com.andharness.ru.ui.activity

import com.andharness.ru.domain.agent.ToolLabels
import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.undo.UndoEntry
import com.andharness.ru.domain.undo.UndoKinds
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AgentActivityMapper] — the Android-free half of the «Что агент сделал» screen.
 * Ordering, the row bound, the audit↔undo match rule, the "is undoable" decision, the human line
 * and the day grouping are all pure and exercised here without any Android runtime.
 */
class AgentActivityLogicTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun audit(
        id: Long,
        at: Long,
        tool: String = "note_create",
        success: Boolean = true,
        argsPreview: String = "{\"text\":\"купить хлеб\"}",
    ) = ToolAuditEntry(
        id = id,
        at = at,
        chatId = 7L,
        tool = tool,
        argsPreview = argsPreview,
        success = success,
        resultHash = "deadbeef",
        durationMs = 5L,
        model = "test-model",
        prevHash = "prev",
        hash = "hash",
    )

    private fun undo(
        id: String = "u1",
        timestamp: Long,
        toolName: String = "note_create",
        kind: String = UndoKinds.NOTE_DELETE,
        description: String = "Создана заметка",
    ) = UndoEntry(
        id = id,
        toolName = toolName,
        description = description,
        timestamp = timestamp,
        undoKind = kind,
        payload = mapOf("id" to "1"),
    )

    @Test
    fun `build orders rows newest first`() {
        val items = AgentActivityMapper.build(
            audit = listOf(audit(1, 100L), audit(2, 300L), audit(3, 200L)),
            undo = emptyList(),
        )
        assertEquals(listOf(2L, 3L, 1L), items.map { it.auditId })
    }

    @Test
    fun `build breaks equal timestamps by descending id`() {
        val items = AgentActivityMapper.build(
            audit = listOf(audit(5, 500L), audit(9, 500L), audit(7, 500L)),
            undo = emptyList(),
        )
        assertEquals(listOf(9L, 7L, 5L), items.map { it.auditId })
    }

    @Test
    fun `build caps the report to the limit`() {
        val rows = (1L..5L).map { audit(it, it * 100L) }
        val items = AgentActivityMapper.build(audit = rows, undo = emptyList(), limit = 2)
        assertEquals(listOf(5L, 4L), items.map { it.auditId })
    }

    @Test
    fun `a nearby same-tool undo entry makes the row undoable`() {
        val items = AgentActivityMapper.build(
            audit = listOf(audit(1, 10_000L)),
            undo = listOf(undo(id = "u", timestamp = 10_400L)),
        )
        assertTrue(items.single().isUndoable)
        assertEquals("u", items.single().undo?.id)
    }

    @Test
    fun `an undo entry for a different tool is not matched`() {
        val items = AgentActivityMapper.build(
            audit = listOf(audit(1, 10_000L, tool = "note_create")),
            undo = listOf(undo(timestamp = 10_000L, toolName = "todo_add")),
        )
        assertFalse(items.single().isUndoable)
        assertNull(items.single().undo)
    }

    @Test
    fun `an undo entry outside the tolerance window is not matched`() {
        val at = 10_000L
        val items = AgentActivityMapper.build(
            audit = listOf(audit(1, at)),
            undo = listOf(undo(timestamp = at + AgentActivityMapper.MATCH_TOLERANCE_MS + 1)),
        )
        assertFalse(items.single().isUndoable)
    }

    @Test
    fun `each undo entry is attached to at most one row`() {
        val items = AgentActivityMapper.build(
            audit = listOf(audit(2, 10_000L), audit(1, 10_000L)),
            undo = listOf(undo(id = "only", timestamp = 10_000L)),
        )
        assertEquals(2, items.size)
        assertEquals(1, items.count { it.isUndoable })
        assertEquals(2L, items.first { it.isUndoable }.auditId)
    }

    @Test
    fun `an unsupported undo kind is never offered`() {
        assertFalse(AgentActivityMapper.isUndoableEntry(null))
        assertFalse(AgentActivityMapper.isUndoableEntry(undo(timestamp = 1L, kind = "not_a_kind")))
        assertTrue(AgentActivityMapper.isUndoableEntry(undo(timestamp = 1L)))

        val items = AgentActivityMapper.build(
            audit = listOf(audit(1, 10_000L)),
            undo = listOf(undo(timestamp = 10_000L, kind = "not_a_kind")),
        )
        assertFalse(items.single().isUndoable)
    }

    @Test
    fun `maps an audit row to a human title and summary`() {
        val item = AgentActivityMapper.build(
            audit = listOf(audit(1, 10_000L, tool = "note_create")),
            undo = emptyList(),
        ).single()
        assertEquals(ToolLabels.label("note_create"), item.title)
        assertEquals("${ToolLabels.label("note_create")} — успешно", AgentActivityMapper.summaryLine(item))

        val failed = AgentActivityMapper.build(
            audit = listOf(audit(2, 10_000L, tool = "note_create", success = false)),
            undo = emptyList(),
        ).single()
        assertEquals("${ToolLabels.label("note_create")} — ошибка", AgentActivityMapper.summaryLine(failed))
    }

    @Test
    fun `groups rows by day with today and yesterday labels`() {
        val today = LocalDate.of(2026, 9, 21)
        val now = today.atStartOfDay(utc).toInstant().toEpochMilli() + 60_000L
        val todayAt = now - 1_000L
        val yesterdayAt = today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli() + 5_000L
        val olderAt = today.minusDays(3).atStartOfDay(utc).toInstant().toEpochMilli() + 5_000L

        val items = AgentActivityMapper.build(
            audit = listOf(audit(3, olderAt), audit(2, yesterdayAt), audit(1, todayAt)),
            undo = emptyList(),
        )
        val groups = AgentActivityMapper.groupByDay(items, now = now, zone = utc)

        assertEquals(listOf("Сегодня", "Вчера", "18.09.2026"), groups.map { it.label })
        assertEquals(listOf(1L), groups[0].items.map { it.auditId })
        assertEquals(listOf(2L), groups[1].items.map { it.auditId })
        assertEquals(listOf(3L), groups[2].items.map { it.auditId })
    }

    @Test
    fun `timeOfDay renders HH mm`() {
        val at = LocalDate.of(2026, 9, 21)
            .atTime(8, 5)
            .atZone(utc)
            .toInstant()
            .toEpochMilli()
        assertEquals("08:05", AgentActivityMapper.timeOfDay(at, utc))
    }
}
