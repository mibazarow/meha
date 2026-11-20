package com.andharness.ru.data.tools.documents

import com.andharness.ru.domain.documents.TemplateRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * JVM tests for the pure parts of `docx_replace`/`docx_append`: text-node replacement inside
 * `word/document.xml`, `{{key}}` mapping and paragraph appending, verified by re-reading the result
 * through [DocumentSupport.docxText] (the very reader `doc_read` uses).
 *
 * References:
 * - OOXML WordprocessingML — https://learn.microsoft.com/en-us/office/open-xml/word/wordprocessingml
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
class DocxTemplateReplaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entryText(bytes: ByteArray, entry: String): String {
        val file = File.createTempFile("probe", ".docx", temp.root).apply { writeBytes(bytes) }
        return ZipFile(file).use { zip ->
            val zipEntry = zip.getEntry(entry)
            assertNotNull("DOCX must contain $entry", zipEntry)
            zip.getInputStream(zipEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    @Test
    fun replacesAPlaceholderInsideARun() {
        val xml = entryText(DocumentWriter.docx("Привет, {{name}}!"), "word/document.xml")
        val updated = DocumentWriter.replaceInTextNodes(xml, "{{name}}", "Иван", listOf("t"))
        val text = DocumentSupport.docxText(updated)
        assertTrue(text.contains("Иван"))
        assertFalse(text.contains("{{name}}"))
    }

    @Test
    fun templateValuesAreWrappedAndApplied() {
        val xml = entryText(DocumentWriter.docx("Итого: {{total}}"), "word/document.xml")
        val replacements = TemplateRenderer.parseReplacements("""{"total":"100"}""", templateStyle = true)
        var updated = xml
        for ((find, replace) in replacements) {
            updated = DocumentWriter.replaceInTextNodes(updated, find, replace, listOf("t"))
        }
        assertEquals("Итого: 100", DocumentSupport.docxText(updated).trim())
    }

    @Test
    fun absentTextLeavesThePartUnchanged() {
        val xml = entryText(DocumentWriter.docx("Текст без меток"), "word/document.xml")
        assertEquals(xml, DocumentWriter.replaceInTextNodes(xml, "{{missing}}", "x", listOf("t")))
    }

    @Test
    fun appendsParagraphsBeforeTheSectionProperties() {
        val xml = entryText(DocumentWriter.docx("Первый"), "word/document.xml")
        val updated = DocumentWriter.appendDocxText(xml, "Второй\nТретий")
        val text = DocumentSupport.docxText(updated)
        assertTrue(text.contains("Первый"))
        assertTrue(text.contains("Второй"))
        assertTrue(text.contains("Третий"))
        // <w:sectPr> must stay the last child of <w:body>.
        assertTrue(updated.lastIndexOf("<w:sectPr") < updated.indexOf("</w:body>"))
    }
}
