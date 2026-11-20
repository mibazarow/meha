package com.andharness.ru.data.tools.documents

import com.rtfparserkit.converter.text.StringTextConverter
import com.rtfparserkit.parser.RtfStreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * JVM tests for the pure edit helpers used by `doc_edit`: paragraph/row insertion into DOCX, ODT,
 * XLSX and ODS parts, prefix-insensitive text-node replacement, RTF paragraph appending and ZIP
 * repackaging. Every edited container is re-read with the very helpers `doc_read` uses, so a
 * regression in the byte layout fails here instead of on the device.
 *
 * References:
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 * - `XmlPullParser` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 * - rtfparserkit (`StringTextConverter`) — https://github.com/joniles/rtfparserkit
 */
class DocumentEditTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private fun entryText(zipFile: File, entry: String): String =
        ZipFile(zipFile).use { zip ->
            val zipEntry = zip.getEntry(entry)
            assertNotNull("ZIP must contain $entry", zipEntry)
            zip.getInputStream(zipEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun assertWellFormed(xml: String) {
        val parser = newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) event = parser.next()
    }

    private fun newPullParser(): XmlPullParser {
        val fromFactory = try {
            XmlPullParserFactory.newInstance().newPullParser()
        } catch (t: Throwable) {
            null
        }
        if (fromFactory != null) return fromFactory
        @Suppress("UNCHECKED_CAST")
        return Class.forName("org.kxml2.io.kXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
    }

    // --- DOCX ---------------------------------------------------------------------------------

    @Test
    fun appendDocxTextAddsParagraphsBeforeBodyEndAndRoundTrips() {
        val original = entryText(file("a.docx", DocumentWriter.docx("Первая")), "word/document.xml")

        val updated = DocumentWriter.appendDocxText(original, "Вторая\nТретья")

        assertWellFormed(updated)
        assertEquals("Первая\nВторая\nТретья", DocumentSupport.docxText(updated))
        assertTrue("sectPr must stay after the new paragraphs", updated.indexOf("Вторая") < updated.indexOf("<w:sectPr>"))
        assertEquals("the body close tag must occur exactly once", 1, updated.split("</w:body>").size - 1)
    }

    @Test
    fun appendDocxTextFailsHonestlyWithoutBodyTag() {
        val error = runCatching { DocumentWriter.appendDocxText("<w:document/>", "x") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
    }

    // --- ODT ----------------------------------------------------------------------------------

    @Test
    fun appendOdtTextAddsParagraphsBeforeOfficeTextEndAndRoundTrips() {
        val original = entryText(file("a.odt", DocumentWriter.odt("Первая")), "content.xml")

        val updated = DocumentWriter.appendOdtText(original, "Вторая & <b>")

        assertWellFormed(updated)
        assertEquals("Первая\nВторая & <b>", DocumentSupport.odfText(updated))
        assertTrue(updated.contains("&amp;"))
        assertTrue(updated.contains("&lt;b&gt;"))
    }

    // --- XLSX ---------------------------------------------------------------------------------

    @Test
    fun appendXlsxRowsContinuesRowNumberingAndRoundTrips() {
        val original = entryText(
            file("a.xlsx", DocumentWriter.xlsx(listOf(listOf("a", "b")))),
            "xl/worksheets/sheet1.xml"
        )

        val updated = DocumentWriter.appendXlsxRows(original, listOf(listOf("c", "d"), listOf("e", "f")))

        assertWellFormed(updated)
        assertTrue("new rows must continue numbering", updated.contains("<row r=\"2\">"))
        assertTrue(updated.contains("<row r=\"3\">"))
        assertEquals("a\tb\nc\td\ne\tf", DocumentSupport.xlsxSheetText(updated, emptyList()))
    }

    @Test
    fun appendXlsxRowsReturnsInputWhenNothingToAdd() {
        val original = entryText(
            file("a.xlsx", DocumentWriter.xlsx(listOf(listOf("a")))),
            "xl/worksheets/sheet1.xml"
        )
        assertEquals(original, DocumentWriter.appendXlsxRows(original, emptyList()))
    }

    // --- ODS ----------------------------------------------------------------------------------

    @Test
    fun appendOdsRowsAddsRowsBeforeTableEndAndRoundTrips() {
        val original = entryText(
            file("a.ods", DocumentWriter.ods(listOf(listOf("a", "b")))),
            "content.xml"
        )

        val updated = DocumentWriter.appendOdsRows(original, listOf(listOf("c", "d")))

        assertWellFormed(updated)
        assertEquals("a\tb\nc\td", DocumentSupport.odsSheetText(updated, null))
    }

    // --- replaceInTextNodes -------------------------------------------------------------------

    @Test
    fun replaceInTextNodesReplacesOnlyInsideComparedElements() {
        val original = entryText(
            file("a.docx", DocumentWriter.docx("Привет мир")),
            "word/document.xml"
        )

        val updated = DocumentWriter.replaceInTextNodes(original, "мир", "друг", listOf("t"))

        assertWellFormed(updated)
        assertEquals("Привет друг", DocumentSupport.docxText(updated))
        assertTrue("markup must survive", updated.contains("<w:p>"))
    }

    @Test
    fun replaceInTextNodesLeavesXmlUntouchedWhenNothingMatches() {
        val original = entryText(
            file("a.docx", DocumentWriter.docx("Привет")),
            "word/document.xml"
        )
        assertEquals(original, DocumentWriter.replaceInTextNodes(original, "нет-такого", "x", listOf("t")))
    }

    @Test
    fun replaceInTextNodesHandlesOdfParagraphs() {
        val original = entryText(file("a.odt", DocumentWriter.odt("Старый текст")), "content.xml")

        val updated = DocumentWriter.replaceInTextNodes(original, "Старый", "Новый", listOf("span", "p"))

        assertWellFormed(updated)
        assertEquals("Новый текст", DocumentSupport.odfText(updated))
    }

    // --- RTF ----------------------------------------------------------------------------------

    @Test
    fun appendRtfAddsParagraphAndRoundTrips() {
        val existing = DocumentWriter.rtf("Первая").toString(Charsets.UTF_8)

        val updated = DocumentWriter.appendRtf(existing, "Вторая")

        assertTrue(updated.startsWith("{\\rtf1\\ansi"))
        assertTrue(updated.endsWith("}"))
        val converted = StringTextConverter().let { converter ->
            converter.convert(RtfStreamSource(ByteArrayInputStream(updated.toByteArray(Charsets.UTF_8))))
            converter.text ?: ""
        }
        val normalized = converted.trim().replace("\r\n", "\n")
        assertTrue("both paragraphs must survive: $normalized", normalized.contains("Первая"))
        assertTrue("both paragraphs must survive: $normalized", normalized.contains("Вторая"))
    }

    // --- rewriteZip ---------------------------------------------------------------------------

    @Test
    fun rewriteZipReplacesEntryAndKeepsStoredMimetypeFirst() {
        val odt = file("a.odt", DocumentWriter.odt("Первая"))
        val originalContent = entryText(odt, "content.xml")
        val updatedContent = DocumentWriter.appendOdtText(originalContent, "Вторая")
        val originalMeta = entryText(odt, "meta.xml")

        val bytes = DocumentSupport.rewriteZip(odt, mapOf("content.xml" to updatedContent.toByteArray(Charsets.UTF_8)))
        assertNotNull(bytes)

        val repacked = file("b.odt", bytes!!)
        val names = ZipFile(repacked).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        assertEquals("mimetype must stay the FIRST entry", "mimetype", names.first())
        ZipFile(repacked).use { zip ->
            val mimetype = zip.getEntry("mimetype")
            assertEquals(ZipEntry.STORED, mimetype.method)
            assertEquals(
                DocumentWriter.ODF_MIMETYPE_TEXT,
                zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.UTF_8) }
            )
        }
        assertEquals("Вторая", DocumentSupport.odfText(entryText(repacked, "content.xml")).split("\n").last())
        assertEquals(originalMeta, entryText(repacked, "meta.xml"))
        assertEquals(DocumentFormat.ODT, DocumentFormatDetector.detect(repacked))
    }

    @Test
    fun rewriteZipKeepsEveryEntryWhenReplacementIsAbsent() {
        val docx = file("a.docx", DocumentWriter.docx("текст"))
        val before = ZipFile(docx).use { zip ->
            zip.entries().asSequence().map { it.name to entryText(docx, it.name) }.toMap()
        }
        val bytes = DocumentSupport.rewriteZip(docx, emptyMap())
        assertNotNull(bytes)
        val repacked = file("b.docx", bytes!!)
        val after = ZipFile(repacked).use { zip ->
            zip.entries().asSequence().map { it.name to entryText(repacked, it.name) }.toMap()
        }
        assertEquals(before, after)
    }
}
