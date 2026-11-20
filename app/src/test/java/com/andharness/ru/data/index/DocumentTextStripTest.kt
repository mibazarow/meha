package com.andharness.ru.data.index

import com.andharness.ru.data.tools.documents.DocumentFormat
import com.andharness.ru.data.tools.documents.DocumentSupport
import com.andharness.ru.data.tools.documents.DocumentTextExtractor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the DOCX text path the document indexer relies on: `word/document.xml` is unzipped and
 * its visible text stripped with the app's existing `DocumentSupport.docxText` (no Apache POI), and the
 * resulting text is what [DocumentChunker] then splits.
 */
class DocumentTextStripTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun docxRunsAreJoinedAndParagraphsBroken() {
        val xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body><w:p><w:r><w:t>Заголовок</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Привет</w:t></w:r><w:r><w:tab/><w:t>мир</w:t></w:r></w:p>" +
            "</w:body></w:document>"
        assertEquals("Заголовок\nПривет\tмир", DocumentSupport.docxText(xml))
    }

    @Test
    fun namespacesAreOptionalAndEntitiesDecoded() {
        val xml = "<document><p><t>a &amp; b</t></p><p><t>c</t></p></document>"
        assertEquals("a & b\nc", DocumentSupport.docxText(xml))
    }

    @Test
    fun breakBecomesNewline() {
        val xml = "<p><t>line1</t><br/><t>line2</t></p>"
        assertEquals("line1\nline2", DocumentSupport.docxText(xml))
    }

    @Test
    fun zippedDocxIsExtractedAndChunkable() {
        val file = temp.newFile("contract.docx")
        writeDocx(file, "Срок действия договора тридцать дней и он продлевается автоматически.")
        val result = DocumentTextExtractor.extract(file)
        val success = result as DocumentTextExtractor.Extracted.Success
        assertEquals(DocumentFormat.DOCX, success.format)
        assertTrue(success.text.contains("тридцать дней"))
        val chunks = DocumentChunker.chunk(success.text)
        assertEquals(1, chunks.size)
        assertTrue(DocumentTypes.accepts(mimeType = "", displayName = "contract.docx"))
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
}
