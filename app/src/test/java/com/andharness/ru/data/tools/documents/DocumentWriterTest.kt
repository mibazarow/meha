package com.andharness.ru.data.tools.documents

import com.rtfparserkit.converter.text.StringTextConverter
import com.rtfparserkit.parser.RtfStreamSource
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
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
 * JVM tests for [DocumentWriter]: every generated container is re-opened (ZIP structure, XML
 * well-formedness, text/cell round-trip through the very readers `doc_read` uses) so a regression in
 * the byte layout fails here rather than on the device.
 *
 * References:
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 * - `XmlPullParser` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 * - rtfparserkit (`StringTextConverter`) — https://github.com/joniles/rtfparserkit
 * - Apache Commons CSV (`CSVParser`) — https://commons.apache.org/proper/commons-csv/
 */
class DocumentWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private fun probeFile(bytes: ByteArray): File =
        File.createTempFile("probe", ".zip", temp.root).apply { writeBytes(bytes) }

    private fun entryNames(bytes: ByteArray): List<String> =
        ZipFile(probeFile(bytes)).use { zip -> zip.entries().asSequence().map { it.name }.toList() }

    private fun entryText(bytes: ByteArray, entry: String): String =
        ZipFile(probeFile(bytes)).use { zip ->
            val zipEntry = zip.getEntry(entry)
            assertNotNull("ZIP must contain $entry", zipEntry)
            zip.getInputStream(zipEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    /** Parses [xml] with the platform parser (kxml2 on a bare JVM) to prove it is well-formed. */
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
        return Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
    }

    // --- DOCX ---------------------------------------------------------------------------------

    @Test
    fun docxContainsRequiredEntriesAndRoundTripsText() {
        val text = "Первая строка & <тег> \"quoted\"\nSecond line"
        val bytes = DocumentWriter.docx(text, title = "Отчёт")

        val names = entryNames(bytes)
        assertTrue(
            "DOCX parts missing: $names",
            names.containsAll(
                listOf(
                    "[Content_Types].xml",
                    "_rels/.rels",
                    "word/document.xml",
                    "word/_rels/document.xml.rels",
                    "docProps/core.xml",
                    "docProps/app.xml"
                )
            )
        )
        for (entry in names) assertWellFormed(entryText(bytes, entry))

        val documentXml = entryText(bytes, "word/document.xml")
        assertEquals(text, DocumentSupport.docxText(documentXml))
        assertTrue("title must be stored", entryText(bytes, "docProps/core.xml").contains("Отчёт"))
        assertTrue("& must be escaped", documentXml.contains("&amp;"))
        assertTrue("< must be escaped", documentXml.contains("&lt;тег&gt;"))
        assertTrue("\" must be escaped", documentXml.contains("&quot;quoted&quot;"))
    }

    @Test
    fun docxIsDetectedAsDocx() {
        val file = file("a.docx", DocumentWriter.docx("hello"))
        assertEquals(DocumentFormat.DOCX, DocumentFormatDetector.detect(file))
    }

    // --- XLSX ---------------------------------------------------------------------------------

    @Test
    fun xlsxContainsRequiredEntriesAndRoundTripsCells() {
        val rows = listOf(listOf("Имя", "Возраст"), listOf("Аня", "30"), listOf("a,b", "c\"d"))
        val bytes = DocumentWriter.xlsx(rows, sheetName = "Данные")

        val names = entryNames(bytes)
        assertTrue(
            "XLSX parts missing: $names",
            names.containsAll(
                listOf(
                    "[Content_Types].xml",
                    "_rels/.rels",
                    "xl/workbook.xml",
                    "xl/_rels/workbook.xml.rels",
                    "xl/worksheets/sheet1.xml",
                    "xl/styles.xml"
                )
            )
        )
        for (entry in names) assertWellFormed(entryText(bytes, entry))

        val workbook = entryText(bytes, "xl/workbook.xml")
        assertEquals(listOf("Данные"), DocumentSupport.xlsxSheetNames(workbook))
        val sheet = entryText(bytes, "xl/worksheets/sheet1.xml")
        assertEquals(
            "Имя\tВозраст\nАня\t30\na,b\tc\"d",
            DocumentSupport.xlsxSheetText(sheet, emptyList())
        )
    }

    @Test
    fun xlsxIsDetectedAsXlsx() {
        val file = file("a.xlsx", DocumentWriter.xlsx(listOf(listOf("x"))))
        assertEquals(DocumentFormat.XLSX, DocumentFormatDetector.detect(file))
    }

    private fun sheetXml(rows: List<List<String>>): String =
        entryText(DocumentWriter.xlsx(rows), "xl/worksheets/sheet1.xml")

    @Test
    fun parseCellReferenceMapsColumnsAndRejectsBadRefs() {
        assertEquals(DocumentWriter.CellAddress(1, 2), DocumentWriter.parseCellReference("B2"))
        assertEquals(DocumentWriter.CellAddress(26, 10), DocumentWriter.parseCellReference("aa10"))
        assertEquals(DocumentWriter.CellAddress(0, 1), DocumentWriter.parseCellReference(" A1 "))
        assertEquals(null, DocumentWriter.parseCellReference("2B"))
        assertEquals(null, DocumentWriter.parseCellReference("A0"))
        assertEquals(null, DocumentWriter.parseCellReference(""))
        assertEquals(null, DocumentWriter.parseCellReference("XFE1"))
    }

    @Test
    fun setXlsxCellReplacesExistingCellWithString() {
        val original = sheetXml(listOf(listOf("old", "keep")))

        val updated = DocumentWriter.setXlsxCell(original, "A1", "новое & <b>")

        assertWellFormed(updated)
        assertEquals("новое & <b>\tkeep", DocumentSupport.xlsxSheetText(updated, emptyList()))
        assertTrue(updated.contains("t=\"inlineStr\""))
        assertTrue(updated.contains("&amp;"))
        assertTrue(updated.contains("&lt;b&gt;"))
    }

    @Test
    fun setXlsxCellReplacesExistingCellWithNumber() {
        val original = sheetXml(listOf(listOf("old")))

        val updated = DocumentWriter.setXlsxCell(original, "A1", "42.5")

        assertWellFormed(updated)
        assertEquals("42.5", DocumentSupport.xlsxSheetText(updated, emptyList()))
        assertTrue("numeric cells must not carry a t attribute", !updated.contains("t=\"inlineStr\""))
        assertTrue(updated.contains("<v>42.5</v>"))
    }

    @Test
    fun setXlsxCellInsertsIntoExistingRowKeepingColumnOrder() {
        val original = """<worksheet xmlns="x"><sheetData>""" +
            """<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c>""" +
            """<c r="C1" t="inlineStr"><is><t>c</t></is></c></row>""" +
            """<row r="2"><c r="A2" t="inlineStr"><is><t>x</t></is></c></row>""" +
            """</sheetData></worksheet>"""

        val updated = DocumentWriter.setXlsxCell(original, "B1", "b")

        assertWellFormed(updated)
        assertEquals("a\tb\tc\nx", DocumentSupport.xlsxSheetText(updated, emptyList()))
        assertTrue("B1 must be ordered before C1", updated.indexOf("r=\"B1\"") < updated.indexOf("r=\"C1\""))
    }

    @Test
    fun setXlsxCellCreatesMissingRow() {
        val original = sheetXml(listOf(listOf("a")))

        val updated = DocumentWriter.setXlsxCell(original, "D4", "42")

        assertWellFormed(updated)
        assertEquals("a\n\t\t\t42", DocumentSupport.xlsxSheetText(updated, emptyList()))
        assertEquals("42", DocumentSupport.xlsxCellValue(updated, emptyList(), "D4"))
    }

    @Test
    fun setXlsxCellCreatesSheetDataWhenSelfClosed() {
        val sheet = """<worksheet xmlns="x"><sheetData/></worksheet>"""

        val updated = DocumentWriter.setXlsxCell(sheet, "B2", "текст")

        assertWellFormed(updated)
        assertEquals("текст", DocumentSupport.xlsxCellValue(updated, emptyList(), "B2"))
        assertTrue(updated.contains("<sheetData>"))
    }

    @Test
    fun setXlsxCellEscapesXmlInInlineStrings() {
        val original = sheetXml(listOf(listOf("x")))

        val updated = DocumentWriter.setXlsxCell(original, "B1", "a\"b'c")

        assertWellFormed(updated)
        assertTrue(updated.contains("&quot;"))
        assertTrue(updated.contains("&apos;"))
        assertEquals("x\ta\"b'c", DocumentSupport.xlsxSheetText(updated, emptyList()))
    }

    @Test
    fun setXlsxCellRejectsMalformedReference() {
        val original = sheetXml(listOf(listOf("x")))
        val error = runCatching { DocumentWriter.setXlsxCell(original, "B", "v") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // --- ODT / ODS ----------------------------------------------------------------------------

    @Test
    fun odtStoresMimetypeFirstUncompressedAndRoundTripsParagraphs() {
        val bytes = DocumentWriter.odt("Строка 1 & <b>\nСтрока 2", title = "Заголовок")

        val names = entryNames(bytes)
        assertEquals("mimetype must be the FIRST entry", "mimetype", names.first())
        assertTrue(
            "ODT parts missing: $names",
            names.containsAll(
                listOf("META-INF/manifest.xml", "content.xml", "meta.xml", "styles.xml")
            )
        )
        for (entry in names) assertWellFormed(entryText(bytes, entry))

        ZipFile(file("odt.zip", bytes)).use { zip ->
            val mimetype = zip.getEntry("mimetype")
            assertEquals(ZipEntry.STORED, mimetype.method)
            assertEquals(
                DocumentWriter.ODF_MIMETYPE_TEXT,
                zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.UTF_8) }
            )
        }
        assertEquals("Заголовок", DocumentSupport.xmlTextContent(entryText(bytes, "meta.xml"), "title"))
        assertEquals("Строка 1 & <b>\nСтрока 2", DocumentSupport.odfText(entryText(bytes, "content.xml")))
    }

    @Test
    fun odsStoresMimetypeFirstUncompressedAndRoundTripsCells() {
        val bytes = DocumentWriter.ods(listOf(listOf("a", "b"), listOf("c", "d")), sheetName = "Лист")

        val names = entryNames(bytes)
        assertEquals("mimetype must be the FIRST entry", "mimetype", names.first())
        for (entry in names) assertWellFormed(entryText(bytes, entry))

        ZipFile(file("ods.zip", bytes)).use { zip ->
            val mimetype = zip.getEntry("mimetype")
            assertEquals(ZipEntry.STORED, mimetype.method)
            assertEquals(
                DocumentWriter.ODF_MIMETYPE_SPREADSHEET,
                zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.UTF_8) }
            )
        }
        val content = entryText(bytes, "content.xml")
        assertEquals(listOf("Лист"), DocumentSupport.odsSheetNames(content))
        assertEquals("a\tb\nc\td", DocumentSupport.odsSheetText(content, "Лист"))
    }

    @Test
    fun odfFormatsAreDetected() {
        assertEquals(DocumentFormat.ODT, DocumentFormatDetector.detect(file("a.odt", DocumentWriter.odt("x"))))
        assertEquals(DocumentFormat.ODS, DocumentFormatDetector.detect(file("a.ods", DocumentWriter.ods(listOf(listOf("x"))))))
    }

    // --- RTF ----------------------------------------------------------------------------------

    @Test
    fun rtfEscapesControlCharactersAndRoundTrips() {
        val text = "a \\ b {c} d\nВторая строка"
        val bytes = DocumentWriter.rtf(text)
        val raw = bytes.toString(Charsets.UTF_8)
        assertTrue("must start with the RTF header", raw.startsWith("{\\rtf1\\ansi"))
        assertTrue("braces must be escaped", raw.contains("\\{c\\}"))
        assertTrue("backslash must be escaped", raw.contains("\\\\"))

        val converted = StringTextConverter().let { converter ->
            converter.convert(RtfStreamSource(ByteArrayInputStream(bytes)))
            converter.text ?: ""
        }
        assertEquals(text, converted.trim().replace("\r\n", "\n"))
    }

    @Test
    fun rtfIsDetectedAsRtf() {
        val file = file("a.rtf", DocumentWriter.rtf("hello"))
        assertEquals(DocumentFormat.RTF, DocumentFormatDetector.detect(file))
    }

    // --- CSV ----------------------------------------------------------------------------------

    @Test
    fun csvQuotesFieldsAndRoundTrips() {
        val rows = listOf(listOf("a,b", "c\"d"), listOf("plain", "with\nnewline"))
        val bytes = DocumentWriter.csv(rows, ',')

        val parsed = CSVParser.parse(
            bytes.toString(Charsets.UTF_8),
            CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(',').get()
        ).use { parser -> parser.records.map { record -> record.toList() } }

        assertEquals(rows, parsed)
    }

    @Test
    fun csvHonoursCustomDelimiter() {
        val bytes = DocumentWriter.csv(listOf(listOf("a", "b")), ';')
        assertTrue(bytes.toString(Charsets.UTF_8).contains("a;b"))
    }

    // --- shared helpers -----------------------------------------------------------------------

    @Test
    fun parseTableTextSplitsOnTabThenSemicolonThenComma() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), DocumentWriter.parseTableText("a\tb\nc\td"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), DocumentWriter.parseTableText("a;b\nc;d"))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), DocumentWriter.parseTableText("a,b\nc,d"))
        assertEquals(listOf(listOf("single")), DocumentWriter.parseTableText("single"))
        assertTrue(DocumentWriter.parseTableText("").isEmpty())
    }

    @Test
    fun columnNameMapsIndexesToSpreadsheetLabels() {
        assertEquals("A", DocumentWriter.columnName(0))
        assertEquals("Z", DocumentWriter.columnName(25))
        assertEquals("AA", DocumentWriter.columnName(26))
        assertEquals("AB", DocumentWriter.columnName(27))
    }

    @Test
    fun xmlSafeDropsIllegalControlCharacters() {
        assertEquals("ab", DocumentWriter.xmlSafe("a\u0000\u0008b"))
        assertEquals("a\tb", DocumentWriter.xmlSafe("a\tb"))
    }
}
