package com.andharness.ru.domain.rag

import com.andharness.ru.data.db.dao.RagChunkDao
import com.andharness.ru.data.db.dao.RagSourceSummary
import com.andharness.ru.data.db.entity.RagChunkEntity
import com.andharness.ru.data.rag.RagIndexer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the RAG indexer/retriever (update.md idea #4): mtime-based skip and `force`,
 * retrieval ranking over real chunk fixtures, honest path/format failures and a small end-to-end
 * index -> query run on a temporary directory (TXT + DOCX; PDF extraction is exercised on-device
 * because pdfbox-android resolves font metrics from Android assets).
 */
class RagIndexerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dao = FakeRagChunkDao()
    private val indexer = RagIndexer(dao)

    @Test
    fun indexesTextAndAnswersWithACitation() = runBlocking {
        val root = temp.newFolder("root")
        val contract = File(root, "contract.txt").apply {
            writeText("Срок действия договора — тридцать календарных дней с даты подписания.")
        }
        File(root, "weather.txt").writeText("Прогноз погоды на завтра: дождь и сильный ветер.")

        val report = indexer.index(root, contract.name, recursive = false, force = false)
        assertEquals(1, report.filesIndexed)
        assertTrue(report.chunksStored >= 1)
        assertTrue(report.errors.isEmpty())

        val result = indexer.query("какие условия про срок договора?", limit = 5)
        assertTrue("expected a hit, note=${result.note}", result.hits.isNotEmpty())
        assertEquals("contract.txt", result.hits.first().sourcePath)
        assertTrue(result.hits.first().text.contains("тридцать"))
        assertTrue(result.hits.first().score > 0.0)
    }

    @Test
    fun unchangedSourceIsSkippedAndForceReindexes() = runBlocking {
        val root = temp.newFolder("root")
        val file = File(root, "note.txt").apply { writeText("Первый вариант текста про оплату.") }

        val first = indexer.index(root, file.name, recursive = false, force = false)
        assertEquals(1, first.filesIndexed)
        assertEquals(0, first.filesSkipped)

        val second = indexer.index(root, file.name, recursive = false, force = false)
        assertEquals(0, second.filesIndexed)
        assertEquals(1, second.filesSkipped)

        val forced = indexer.index(root, file.name, recursive = false, force = true)
        assertEquals(1, forced.filesIndexed)
        assertEquals(0, forced.filesSkipped)
    }

    @Test
    fun changedMtimeTriggersReindexWithoutForce() = runBlocking {
        val root = temp.newFolder("root")
        val file = File(root, "note.txt").apply { writeText("Старый текст.") }
        indexer.index(root, file.name, recursive = false, force = false)

        file.writeText("Новый текст про изменённый срок.")
        file.setLastModified(file.lastModified() + 10_000)

        val again = indexer.index(root, file.name, recursive = false, force = false)
        assertEquals(1, again.filesIndexed)
    }

    @Test
    fun reindexReplacesOldChunksInsteadOfAppending() = runBlocking {
        val root = temp.newFolder("root")
        val file = File(root, "note.txt").apply { writeText("а".repeat(2_000)) }
        val first = indexer.index(root, file.name, recursive = false, force = true)
        val firstCount = dao.countBySource("note.txt")
        assertEquals(first.chunksStored, firstCount)

        file.writeText("Короткий новый текст.")
        file.setLastModified(file.lastModified() + 10_000)
        indexer.index(root, file.name, recursive = false, force = true)

        assertEquals(1, dao.countBySource("note.txt"))
    }

    @Test
    fun directoryIndexingRespectsRecursiveFlag() = runBlocking {
        val root = temp.newFolder("root")
        val docs = File(root, "docs").apply { mkdirs() }
        File(docs, "top.txt").writeText("Верхний файл про лимиты.")
        val nested = File(docs, "nested").apply { mkdirs() }
        File(nested, "deep.txt").writeText("Вложенный файл про штрафы.")

        val shallow = indexer.index(root, "docs", recursive = false, force = true)
        assertEquals(1, shallow.filesIndexed)

        val deep = indexer.index(root, "docs", recursive = true, force = true)
        assertEquals(2, deep.filesIndexed)
        assertEquals(2, indexer.stats().size)
    }

    @Test
    fun endToEndIndexAndQueryOverTxtAndDocx() = runBlocking {
        val root = temp.newFolder("root")
        val docs = File(root, "docs").apply { mkdirs() }
        File(docs, "terms.txt").writeText(
            "Пункт 4.2: арендная плата вносится ежемесячно до пятого числа."
        )
        writeDocx(File(docs, "policy.docx"), "Пункт 7.1: расторжение договора возможно за 30 дней.")

        val report = indexer.index(root, "docs", recursive = true, force = false)
        assertEquals(2, report.filesIndexed)
        assertEquals(2, indexer.stats().size)

        val payment = indexer.query("когда нужно платить аренду?", limit = 3)
        assertTrue(payment.hits.isNotEmpty())
        assertEquals("docs/terms.txt", payment.hits.first().sourcePath)

        val termination = indexer.query("за сколько дней можно расторгнуть договор?", limit = 3)
        assertTrue(termination.hits.isNotEmpty())
        assertEquals("docs/policy.docx", termination.hits.first().sourcePath)
        assertEquals(0, termination.hits.first().chunkIndex)
    }

    @Test
    fun pathEscapeIsReportedNotThrown() = runBlocking {
        val root = temp.newFolder("root")
        val report = indexer.index(root, "../outside.txt", recursive = false, force = false)
        assertEquals(1, report.filesFailed)
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun unknownBinaryFormatFailsHonestly() = runBlocking {
        val root = temp.newFolder("root")
        File(root, "fake.pdf").writeText("this is definitely not a pdf")
        val report = indexer.index(root, "fake.pdf", recursive = false, force = false)
        assertEquals(1, report.filesFailed)
        assertTrue(report.errors.first().contains("не индексируется"))
    }

    @Test
    fun queryOnEmptyIndexReportsAnHonestNote() = runBlocking {
        val result = indexer.query("что угодно", limit = 5)
        assertTrue(result.hits.isEmpty())
        assertTrue(result.note!!.contains("Индекс пуст"))
    }

    @Test
    fun clearSourceRemovesOnlyThatSource() = runBlocking {
        val root = temp.newFolder("root")
        File(root, "a.txt").writeText("Документ про оплату.")
        File(root, "b.txt").writeText("Документ про доставку.")
        indexer.index(root, "a.txt", recursive = false, force = false)
        indexer.index(root, "b.txt", recursive = false, force = false)
        assertEquals(2, indexer.stats().size)

        indexer.clearSource("a.txt")
        assertEquals(listOf("b.txt"), indexer.stats().map { it.sourcePath })
        assertFalse(indexer.stats().any { it.sourcePath == "a.txt" })
    }

    private fun writeDocx(target: File, text: String) {
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>"
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** In-memory [RagChunkDao] so the indexer can be tested without Room (and without a device). */
    private class FakeRagChunkDao : RagChunkDao {
        private val rows = mutableListOf<RagChunkEntity>()
        private var nextId = 1L

        override suspend fun insertAll(chunks: List<RagChunkEntity>) {
            chunks.forEach { rows += it.copy(id = nextId++) }
        }

        override suspend fun deleteBySource(path: String) {
            rows.removeAll { it.sourcePath == path }
        }

        override suspend fun deleteAll() = rows.clear()

        override suspend fun count(): Int = rows.size

        override suspend fun countBySource(path: String): Int =
            rows.count { it.sourcePath == path }

        override suspend fun sourceMtime(path: String): Long? =
            rows.filter { it.sourcePath == path }.maxOfOrNull { it.updatedAt }

        override suspend fun all(): List<RagChunkEntity> = rows.toList()

        override suspend fun byPathPrefix(prefix: String): List<RagChunkEntity> =
            rows.filter { it.sourcePath.startsWith(prefix) }

        override suspend fun sources(): List<String> =
            rows.map { it.sourcePath }.distinct().sorted()

        override suspend fun sourceSummary(): List<RagSourceSummary> =
            rows.groupBy { it.sourcePath }
                .map { (path, group) ->
                    RagSourceSummary(path, group.size, group.maxOf { it.updatedAt })
                }
                .sortedBy { it.sourcePath }
    }
}
