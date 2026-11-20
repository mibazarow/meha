package com.andharness.ru.data.tools.undo

import com.andharness.ru.data.undo.UndoStore
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.undo.UndoAction
import com.andharness.ru.domain.undo.UndoEntry
import com.andharness.ru.domain.undo.UndoExecutor
import com.andharness.ru.domain.undo.UndoKinds
import com.andharness.ru.domain.undo.UndoOutcome
import com.andharness.ru.domain.undo.UndoPayload
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the two undo tools at the handler boundary: [undo_list] is a read-only feed and
 * [undo_last] reverts the most recent entry, but must always be honest when there is nothing to undo
 * or the inverse cannot be applied. `UndoStore` is file-backed and Android-free, so a temp dir is
 * enough here.
 */
class UndoToolsHandlerTest {

    private val dirs = mutableListOf<File>()

    private fun newStore(): UndoStore =
        UndoStore(Files.createTempDirectory("undo-tools-test").toFile().also { dirs += it })

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun entry(id: String, kind: String = UndoKinds.NOTE_DELETE) = UndoEntry(
        id = id,
        toolName = "note_create",
        description = "Создана заметка #$id",
        timestamp = id.hashCode().toLong(),
        undoKind = kind,
        payload = mapOf(UndoPayload.ID to id),
    )

    private fun handler(name: String, store: UndoStore, executor: UndoExecutor): ToolHandler =
        UndoToolsHandler(store, executor).handlers().first { it.name == name }

    private fun emptyArgs(): JsonObject = buildJsonObject { }

    @Test
    fun `undo_list reports an honest empty journal`() = runTest {
        val result = handler("undo_list", newStore(), UndoExecutor(emptyMap())).execute(emptyArgs())
        assertTrue(result.success)
        assertTrue(result.output.contains("пуст"))
    }

    @Test
    fun `undo_list shows recent actions newest first with descriptions`() = runTest {
        val store = newStore()
        store.record(entry("a"))
        store.record(entry("b"))

        val result = handler("undo_list", store, UndoExecutor(emptyMap())).execute(emptyArgs())

        assertTrue(result.success)
        val lines = result.output.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Создана заметка #b"))
        assertTrue(lines[1].contains("Создана заметка #a"))
        assertTrue(lines[0].contains("note_create"))
    }

    @Test
    fun `undo_list honours the limit argument`() = runTest {
        val store = newStore()
        store.record(entry("a"))
        store.record(entry("b"))
        store.record(entry("c"))

        val result = handler("undo_list", store, UndoExecutor(emptyMap()))
            .execute(buildJsonObject { put("limit", "1") })

        assertEquals(1, result.output.lines().size)
        assertTrue(result.output.contains("#c"))
    }

    @Test
    fun `undo_last on an empty journal is an honest failure`() = runTest {
        val store = newStore()
        val result = handler("undo_last", store, UndoExecutor(emptyMap())).execute(emptyArgs())

        assertFalse(result.success)
        assertTrue(result.output.contains("Нечего отменять"))
    }

    @Test
    fun `undo_last applies the inverse and pops the entry`() = runTest {
        val store = newStore()
        store.record(entry("a"))
        var undone: Map<String, String>? = null
        val executor = UndoExecutor(
            mapOf(UndoKinds.NOTE_DELETE to UndoAction { payload ->
                undone = payload
                UndoOutcome(true, "Заметка удалена.")
            }),
        )

        val result = handler("undo_last", store, executor).execute(emptyArgs())

        assertTrue(result.success)
        assertTrue(result.output.contains("Создана заметка #a"))
        assertEquals(mapOf(UndoPayload.ID to "a"), undone)
        assertEquals(0, store.count())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `undo_last keeps the newest entry in the journal when the inverse fails`() = runTest {
        val store = newStore()
        store.record(entry("a"))
        val failing = UndoExecutor(
            mapOf(
                UndoKinds.NOTE_DELETE to UndoAction {
                    UndoOutcome(false, "хранилище недоступно")
                },
            ),
        )

        val result = handler("undo_last", store, failing).execute(emptyArgs())

        assertFalse(result.success)
        assertTrue(result.output.contains("хранилище недоступно"))
        assertEquals(1, store.count())
        assertEquals("a", store.all().single().id)
    }

    @Test
    fun `undo_last reports honestly when the inverse is unknown or fails`() = runTest {
        val store = newStore()
        store.record(entry("a", kind = "some_foreign_kind"))

        val unknown = handler("undo_last", store, UndoExecutor(emptyMap())).execute(emptyArgs())
        assertFalse(unknown.success)
        assertTrue(unknown.output.contains("нельзя"))
        assertEquals(1, store.count())
        assertEquals("a", store.all().single().id)

        store.record(entry("b"))
        val failing = handler(
            "undo_last",
            store,
            UndoExecutor(mapOf(UndoKinds.NOTE_DELETE to UndoAction { UndoOutcome(false, "не найдено") })),
        ).execute(emptyArgs())
        assertFalse(failing.success)
        assertTrue(failing.output.contains("не найдено"))
        assertEquals(2, store.count())
    }
}
