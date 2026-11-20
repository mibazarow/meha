package com.andharness.ru.data.undo

import com.andharness.ru.domain.undo.UndoCodec
import com.andharness.ru.domain.undo.UndoEntry
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoPayload
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [UndoStore]. It is Android-free by construction (it only takes a
 * directory), so real file CRUD, persistence across instances, the "last N" bound, newest-first
 * listing and `popLast` ordering are all exercised on a temp directory — no Android runtime.
 */
class UndoStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("undo-store-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = UndoStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun entry(id: String, timestamp: Long = 0L): UndoEntry = UndoEntry(
        id = id,
        toolName = "note_create",
        description = "Заметка $id",
        timestamp = timestamp,
        undoKind = UndoKinds.NOTE_DELETE,
        payload = mapOf(UndoPayload.ID to id),
    )

    @Test
    fun `record appends and list returns newest first`() {
        val store = store()
        store.record(entry("a", 1L))
        store.record(entry("b", 2L))
        store.record(entry("c", 3L))

        assertEquals(listOf("c", "b", "a"), store.list().map { it.id })
        assertEquals(listOf("a", "b", "c"), store.all().map { it.id })
        assertEquals(3, store.count())
    }

    @Test
    fun `list honours the limit`() {
        val store = store()
        (1..5).forEach { store.record(entry("e$it")) }
        assertEquals(listOf("e5", "e4"), store.list(2).map { it.id })
        assertTrue(store.list(0).isEmpty())
    }

    @Test
    fun `record is bounded to the last MAX_ENTRIES`() {
        val store = store()
        (1..UndoCodec.MAX_ENTRIES + 10).forEach { store.record(entry("e$it")) }

        assertEquals(UndoCodec.MAX_ENTRIES, store.count())
        assertEquals("e11", store.all().first().id)
        assertEquals("e${UndoCodec.MAX_ENTRIES + 10}", store.all().last().id)
    }

    @Test
    fun `popLast removes the newest and keeps ordering`() {
        val store = store()
        store.record(entry("a"))
        store.record(entry("b"))
        store.record(entry("c"))

        assertEquals("c", store.popLast()?.id)
        assertEquals("b", store.popLast()?.id)
        assertEquals(listOf("a"), store.all().map { it.id })
        assertEquals("a", store.popLast()?.id)
        assertNull(store.popLast())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `remove deletes by id and reports honestly`() {
        val store = store()
        store.record(entry("a"))
        store.record(entry("b"))

        assertTrue(store.remove("a"))
        assertFalse(store.remove("missing"))
        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test
    fun `clear empties the journal`() {
        val store = store()
        store.record(entry("a"))
        store.clear()
        assertTrue(store.all().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun `state flow reflects every mutation`() {
        val store = store()
        store.record(entry("a"))
        assertEquals(listOf("a"), store.entries.value.map { it.id })
        store.record(entry("b"))
        assertEquals(listOf("a", "b"), store.entries.value.map { it.id })
        store.clear()
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `entries persist across store instances`() {
        val dir = newDir()
        store(dir).record(entry("a", 11L))
        val reopened = store(dir)
        assertEquals(1, reopened.count())
        assertEquals("a", reopened.all().first().id)
        assertEquals(11L, reopened.all().first().timestamp)
    }

    @Test
    fun `handy recorder overload generates an id and timestamps the entry`() {
        val store = store()
        store.record(
            toolName = "todo_add",
            description = "Дело",
            undoKind = UndoKinds.TODO_DELETE,
            payload = mapOf(UndoPayload.ID to "7"),
        )
        val only = store.all().single()
        assertTrue(only.id.isNotBlank())
        assertTrue(only.timestamp > 0L)
        assertEquals("todo_add", only.toolName)
        assertEquals(mapOf(UndoPayload.ID to "7"), only.payload)
    }

    @Test
    fun `corrupt journal file degrades to an empty store`() {
        val dir = newDir()
        File(dir, UndoStore.FILE_NAME).writeText("{ totally broken")
        assertTrue(store(dir).all().isEmpty())
    }
}
