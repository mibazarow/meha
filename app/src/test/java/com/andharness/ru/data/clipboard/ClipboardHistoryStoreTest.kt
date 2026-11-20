package com.andharness.ru.data.clipboard

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [ClipboardHistoryStore] and the pure "smart paste" logic
 * ([ClipboardHistoryLogic]). The store is Android-free by construction (it only takes a directory),
 * so real file CRUD, the entry bound, the length cap, consecutive dedupe, persistence across
 * instances and corrupt-file degradation are exercised on a temp directory — no Android runtime.
 */
class ClipboardHistoryStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("clipboard-history-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = ClipboardHistoryStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `record appends and list returns newest first`() {
        val store = store()
        store.record("a", now = 1L)
        store.record("b", now = 2L)
        store.record("c", now = 3L)

        assertEquals(listOf("c", "b", "a"), store.list().map { it.text })
        assertEquals(listOf("a", "b", "c"), store.all().map { it.text })
        assertEquals(3, store.count())
    }

    @Test
    fun `list honours the limit`() {
        val store = store()
        (1..5).forEach { store.record("e$it") }
        assertEquals(listOf("e5", "e4"), store.list(2).map { it.text })
        assertTrue(store.list(0).isEmpty())
    }

    @Test
    fun `blank text is never stored`() {
        val store = store()
        assertNull(store.record(""))
        assertNull(store.record("   "))
        assertNull(store.record("\n\t "))
        assertEquals(0, store.count())
    }

    @Test
    fun `consecutive duplicates are not re-stored`() {
        val store = store()
        store.record("same", now = 1L)
        store.record("same", now = 2L)
        store.record("same", now = 3L)

        assertEquals(1, store.count())
        assertEquals(1L, store.latest()?.timestamp)
    }

    @Test
    fun `non-consecutive duplicates are kept`() {
        val store = store()
        store.record("a")
        store.record("b")
        store.record("a")
        assertEquals(listOf("a", "b", "a"), store.all().map { it.text })
    }

    @Test
    fun `stored text is trimmed and length capped`() {
        val store = store()
        store.record("  hello  ")
        assertEquals("hello", store.latest()?.text)

        val huge = "x".repeat(ClipboardHistoryRules.MAX_TEXT_CHARS + 500)
        store.record(huge)
        assertEquals(ClipboardHistoryRules.MAX_TEXT_CHARS, store.latest()?.text?.length)
    }

    @Test
    fun `record is bounded to the last MAX_ENTRIES`() {
        val store = store()
        (1..ClipboardHistoryRules.MAX_ENTRIES + 10).forEach { store.record("e$it") }

        assertEquals(ClipboardHistoryRules.MAX_ENTRIES, store.count())
        assertEquals("e11", store.all().first().text)
        assertEquals("e${ClipboardHistoryRules.MAX_ENTRIES + 10}", store.all().last().text)
    }

    @Test
    fun `source package is remembered and defaults to null`() {
        val store = store()
        store.record("from app", sourcePackage = "com.andharness.ru")
        store.record("from clipboard")
        assertEquals("com.andharness.ru", store.all().first().sourcePackage)
        assertEquals(null, store.latest()?.sourcePackage)
    }

    @Test
    fun `delete removes by id and reports honestly`() {
        val store = store()
        val first = store.record("a")!!
        store.record("b")

        assertTrue(store.delete(first.id))
        assertFalse(store.delete("missing"))
        assertEquals(listOf("b"), store.all().map { it.text })
    }

    @Test
    fun `clear empties the history`() {
        val store = store()
        store.record("a")
        store.record("b")
        store.clear()
        assertTrue(store.all().isEmpty())
        assertEquals(0, store.count())
        assertNull(store.latest())
    }

    @Test
    fun `state flow reflects every mutation`() {
        val store = store()
        store.record("a")
        assertEquals(listOf("a"), store.entries.value.map { it.text })
        store.record("b")
        assertEquals(listOf("a", "b"), store.entries.value.map { it.text })
        store.clear()
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `history persists across store instances`() {
        val dir = newDir()
        store(dir).record("persisted", sourcePackage = "pkg", now = 11L)
        val reopened = store(dir)
        assertEquals(1, reopened.count())
        assertEquals("persisted", reopened.latest()?.text)
        assertEquals("pkg", reopened.latest()?.sourcePackage)
        assertEquals(11L, reopened.latest()?.timestamp)
    }

    @Test
    fun `corrupt history file degrades to an empty store`() {
        val dir = newDir()
        File(dir, ClipboardHistoryStore.FILE_NAME).writeText("{ totally broken")
        assertTrue(store(dir).all().isEmpty())
    }

    // ---- smart paste selection (ClipboardHistoryLogic.select) ----

    private fun entries(): List<ClipboardEntry> = listOf(
        ClipboardEntry("id-1", "oldest"),
        ClipboardEntry("id-2", "middle"),
        ClipboardEntry("id-3", "newest"),
    )

    @Test
    fun `select without parameters returns the newest entry`() {
        assertEquals("newest", ClipboardHistoryLogic.select(entries())?.text)
        assertNull(ClipboardHistoryLogic.select(emptyList()))
    }

    @Test
    fun `select by index is one-based newest first`() {
        assertEquals("newest", ClipboardHistoryLogic.select(entries(), index = 1)?.text)
        assertEquals("middle", ClipboardHistoryLogic.select(entries(), index = 2)?.text)
        assertEquals("oldest", ClipboardHistoryLogic.select(entries(), index = 3)?.text)
        assertNull(ClipboardHistoryLogic.select(entries(), index = 0))
        assertNull(ClipboardHistoryLogic.select(entries(), index = 4))
    }

    @Test
    fun `select by id wins over index and reports misses honestly`() {
        assertEquals("middle", ClipboardHistoryLogic.select(entries(), index = 1, id = "id-2")?.text)
        assertNull(ClipboardHistoryLogic.select(entries(), id = "missing"))
        assertNull(ClipboardHistoryLogic.select(entries(), index = 1, id = "missing"))
    }

    @Test
    fun `preview is single line and bounded`() {
        assertEquals("a b", ClipboardHistoryLogic.preview("a\nb"))
        val long = "y".repeat(ClipboardHistoryLogic.PREVIEW_CHARS + 10)
        val preview = ClipboardHistoryLogic.preview(long)
        assertEquals(ClipboardHistoryLogic.PREVIEW_CHARS + 1, preview.length)
        assertTrue(preview.endsWith("…"))
        assertEquals("", ClipboardHistoryLogic.preview("x", maxChars = 0))
    }
}
