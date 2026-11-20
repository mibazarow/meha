package com.andharness.ru.domain.undo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [UndoCodec]: round-trip, the "last N" bound and corrupt-input degradation.
 * No Android runtime involved.
 */
class UndoCodecTest {

    private fun entry(id: String, timestamp: Long = id.hashCode().toLong()) = UndoEntry(
        id = id,
        toolName = "note_create",
        description = "Заметка $id",
        timestamp = timestamp,
        undoKind = UndoKinds.NOTE_DELETE,
        payload = mapOf(UndoPayload.ID to id),
    )

    @Test
    fun `encode then decode round trips every field and order`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val decoded = UndoCodec.decode(UndoCodec.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun `encode keeps only the last MAX_ENTRIES`() {
        val entries = (1..UndoCodec.MAX_ENTRIES + 10).map { entry("e$it") }
        val decoded = UndoCodec.decode(UndoCodec.encode(entries))
        assertEquals(UndoCodec.MAX_ENTRIES, decoded.size)
        assertEquals("e11", decoded.first().id)
        assertEquals("e${UndoCodec.MAX_ENTRIES + 10}", decoded.last().id)
    }

    @Test
    fun `decode drops an oversized historical file to the last MAX_ENTRIES`() {
        val raw = UndoCodec.encode((1..UndoCodec.MAX_ENTRIES + 5).map { entry("e$it") })
        val decoded = UndoCodec.decode(raw)
        assertEquals(UndoCodec.MAX_ENTRIES, decoded.size)
        assertEquals("e6", decoded.first().id)
    }

    @Test
    fun `decode of null or blank is an empty list`() {
        assertTrue(UndoCodec.decode(null).isEmpty())
        assertTrue(UndoCodec.decode("").isEmpty())
    }

    @Test
    fun `decode of corrupt or foreign JSON degrades to empty`() {
        assertTrue(UndoCodec.decode("not json at all").isEmpty())
        assertTrue(UndoCodec.decode("""{"unexpected":"object"}""").isEmpty())
        assertTrue(UndoCodec.decode("[{\"missing\":\"id\"}]").isEmpty())
    }

    @Test
    fun `ignores unknown keys so a newer schema still decodes`() {
        val raw = "[{\"id\":\"x\",\"toolName\":\"note_create\",\"description\":\"d\"," +
            "\"timestamp\":1,\"undoKind\":\"note_delete\",\"payload\":{},\"future\":true}]"
        val decoded = UndoCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("x", decoded.first().id)
    }
}
