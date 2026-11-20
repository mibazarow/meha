package com.andharness.ru.data.profile

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [GlobalProfileStore] and the pure [ProfileRules]/[GlobalProfileCodec].
 *
 * The store is Android-free by construction (it only takes a directory), so real file CRUD,
 * persistence across instances, dedupe, the entry cap, the 1200-char prompt block, atomic writes and
 * corrupt-file degradation are all exercised on a temp directory — no Room/Android runtime involved.
 */
class GlobalProfileStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("global-profile-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = GlobalProfileStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    fun newStoreIsEmptyAndRendersNothing() {
        val store = store()
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.count())
        assertEquals("an empty profile must render nothing (no header)", "", store.renderBlock())
        assertEquals(ProfileWriteOutcome.NOT_FOUND, store.put("nope", "x").outcome)
    }

    @Test
    fun putCreatesAndListsAFactWithTimestamps() {
        val store = store()
        val result = store.put(null, "  Люблю   зелёный чай ", now = 1_000L)

        assertEquals(ProfileWriteOutcome.CREATED, result.outcome)
        val fact = result.fact
        assertNotNull(fact)
        assertEquals("whitespace must be collapsed", "Люблю зелёный чай", fact!!.text)
        assertEquals(1_000L, fact.createdAt)
        assertEquals(1_000L, fact.updatedAt)
        assertEquals(1, store.count())
        assertEquals(fact, store.get(fact.id))
    }

    @Test
    fun eachCreatedFactGetsADistinctId() {
        val store = store()
        val a = store.put(null, "первый").fact!!
        val b = store.put(null, "второй").fact!!
        assertNotEquals(a.id, b.id)
        assertEquals(2, store.count())
    }

    @Test
    fun putRejectsBlankAndOverlongTextWithoutWriting() {
        val store = store()
        assertEquals(ProfileWriteOutcome.INVALID, store.put(null, "   ").outcome)
        assertEquals(ProfileWriteOutcome.INVALID, store.put(null, "").outcome)
        assertEquals(
            ProfileWriteOutcome.INVALID,
            store.put(null, "x".repeat(ProfileRules.MAX_TEXT_CHARS + 1)).outcome
        )
        // A text exactly at the cap is accepted.
        assertEquals(
            ProfileWriteOutcome.CREATED,
            store.put(null, "y".repeat(ProfileRules.MAX_TEXT_CHARS)).outcome
        )
        assertEquals("only the valid fact may be stored", 1, store.count())
    }

    @Test
    fun putDeduplicatesIdenticalTextCaseInsensitively() {
        val store = store()
        val first = store.put(null, "Люблю чай").fact!!
        val second = store.put(null, "  люблю ЧАЙ  ")

        assertEquals(ProfileWriteOutcome.DUPLICATE, second.outcome)
        assertEquals("the duplicate must point at the existing fact", first.id, second.fact?.id)
        assertEquals(1, store.count())
    }

    @Test
    fun putWithIdUpdatesTextAndBumpsOrderButKeepsCreatedAt() {
        val store = store()
        val a = store.put(null, "факт A", now = 10L).fact!!
        val b = store.put(null, "факт B", now = 20L).fact!!

        val updated = store.put(a.id, "факт A v2", now = 30L)

        assertEquals(ProfileWriteOutcome.UPDATED, updated.outcome)
        assertEquals(a.id, updated.fact?.id)
        assertEquals(10L, updated.fact?.createdAt)
        assertEquals(30L, updated.fact?.updatedAt)
        // The updated fact moves to the end (storage order = least recently written first).
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
    }

    @Test
    fun putWithIdIsANoOpDuplicateWhenTheTextIsUnchanged() {
        val store = store()
        val a = store.put(null, "Люблю чай", now = 10L).fact!!
        val again = store.put(a.id, "люблю чай", now = 20L)

        assertEquals(ProfileWriteOutcome.DUPLICATE, again.outcome)
        assertEquals(a.id, again.fact?.id)
        assertEquals("no write must happen for an unchanged text", 10L, store.get(a.id)?.updatedAt)
    }

    @Test
    fun putWithUnknownIdReportsNotFoundAndAddsNothing() {
        val store = store()
        store.put(null, "есть факт")
        assertEquals(ProfileWriteOutcome.NOT_FOUND, store.put("missing", "новый").outcome)
        assertEquals(1, store.count())
    }

    @Test
    fun updatingToAnotherFactsTextIsReportedAsDuplicate() {
        val store = store()
        val a = store.put(null, "первый факт").fact!!
        val b = store.put(null, "второй факт").fact!!

        val result = store.put(b.id, "первый факт")

        assertEquals(ProfileWriteOutcome.DUPLICATE, result.outcome)
        assertEquals(a.id, result.fact?.id)
        assertEquals("the other fact must stay untouched", "второй факт", store.get(b.id)?.text)
        assertEquals(2, store.count())
    }

    @Test
    fun deleteAndClearRemoveFactsHonestly() {
        val store = store()
        val a = store.put(null, "один").fact!!
        store.put(null, "два")

        assertFalse(store.delete("nope"))
        assertTrue(store.delete(a.id))
        assertFalse(store.delete(a.id))
        assertEquals(1, store.count())

        assertTrue(store.clear())
        assertFalse("clearing an empty profile must report false", store.clear())
        assertTrue(store.list().isEmpty())
    }

    // ------------------------------------------------------------------ bounds

    @Test
    fun entryCountIsBoundedAndDropsTheStalest() {
        val store = store()
        val total = ProfileRules.MAX_ENTRIES + 10
        repeat(total) { index ->
            store.put(null, "факт $index", now = index.toLong())
        }

        assertEquals(ProfileRules.MAX_ENTRIES, store.count())
        assertTrue("the oldest facts must be dropped", store.list().none { it.text == "факт 0" })
        assertTrue("the newest fact must survive", store.list().any { it.text == "факт ${total - 1}" })
    }

    // ------------------------------------------------------------------ persistence / atomicity

    @Test
    fun factsPersistAcrossInstancesAndLeaveNoTempFile() {
        val dir = newDir()
        val first = GlobalProfileStore(dir)
        val fact = first.put(null, "Живу в Москве", now = 42L).fact!!

        // A single JSON file must exist and no temp file may be left behind (atomic temp+rename).
        assertTrue(File(dir, GlobalProfileStore.FILE_NAME).isFile)
        assertFalse(File(dir, "${GlobalProfileStore.FILE_NAME}.tmp").exists())

        val second = GlobalProfileStore(dir)
        assertEquals(1, second.count())
        assertEquals(fact, second.get(fact.id))
        assertEquals(42L, second.get(fact.id)?.createdAt)
    }

    @Test
    fun corruptFileDegradesToEmptyAndStaysWritable() {
        val dir = newDir()
        dir.mkdirs()
        File(dir, GlobalProfileStore.FILE_NAME).writeText("{ not valid json")

        val store = GlobalProfileStore(dir)
        assertTrue(store.list().isEmpty())
        assertEquals("", store.renderBlock())

        assertEquals(ProfileWriteOutcome.CREATED, store.put(null, "новый факт").outcome)
        assertEquals(1, store.count())
    }

    @Test
    fun codecDropsBlankIdsAndDuplicatesAndCapsTheCount() {
        val raw = GlobalProfileCodec.encode(
            listOf(
                ProfileFact("", "пустой id", 1, 1),
                ProfileFact("a", "  ", 1, 1),
                ProfileFact("b", "Повтор", 1, 1),
                ProfileFact("c", "повтор", 1, 1),
                ProfileFact("d", "уникальный", 1, 1),
            )
        )
        val decoded = GlobalProfileCodec.decode(raw)
        assertEquals(listOf("b", "d"), decoded.map { it.id })
    }

    // ------------------------------------------------------------------ renderBlock

    @Test
    fun renderBlockListsFactsInStorageOrderUnderTheHeader() {
        val store = store()
        store.put(null, "Имя: Иван")
        store.put(null, "Любит чай")

        val block = store.renderBlock()

        assertTrue(block.startsWith(ProfileRules.HEADER))
        val nameIndex = block.indexOf("Имя: Иван")
        val teaIndex = block.indexOf("Любит чай")
        assertTrue("facts must appear oldest first", nameIndex in 0 until teaIndex)
        assertTrue("each fact must carry its id", block.contains("["))
    }

    @Test
    fun renderBlockIsHardBoundedAndSaysWhenItTruncates() {
        val store = store()
        repeat(30) { index -> store.put(null, "факт номер $index " + "x".repeat(80)) }

        val block = store.renderBlock()

        assertTrue("the block must never exceed the cap", block.length <= ProfileRules.MAX_BLOCK_CHARS)
        assertTrue(block.startsWith(ProfileRules.HEADER))
        assertTrue("truncation must be stated honestly", block.contains("не показано"))
    }

    @Test
    fun renderBlockHonoursAnExplicitSmallLimit() {
        val store = store()
        store.put(null, "Имя: Иван")
        store.put(null, "Любит чай")
        assertEquals("", store.renderBlock(limit = 0))
        val tiny = store.renderBlock(limit = 10)
        assertTrue(tiny.length <= 10)
        assertTrue(ProfileRules.HEADER.startsWith(tiny))
    }

    @Test
    fun sanitizeKeepsTheNewestWhenOverTheCap() {
        val over = (0 until ProfileRules.MAX_ENTRIES + 5).map {
            ProfileFact("id$it", "факт $it", it.toLong(), it.toLong())
        }
        val sanitized = ProfileRules.sanitize(over)
        assertEquals(ProfileRules.MAX_ENTRIES, sanitized.size)
        assertTrue(sanitized.none { it.text == "факт 0" })
        assertEquals("факт ${ProfileRules.MAX_ENTRIES + 4}", sanitized.last().text)
    }

    @Test
    fun normalizeTextCollapsesWhitespaceAndRejectsOverlongInput() {
        assertEquals("a b c", ProfileRules.normalizeText("  a\n b\t\tc  "))
        assertNull(ProfileRules.normalizeText("   "))
        assertNull(ProfileRules.normalizeText(null))
        assertNull(ProfileRules.normalizeText("x".repeat(ProfileRules.MAX_TEXT_CHARS + 1)))
    }

    @Test
    fun sameTextIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(ProfileRules.sameText("Люблю Чай", "  люблю чай "))
        assertFalse(ProfileRules.sameText("чай", "кофе"))
    }
}
