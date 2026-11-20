package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for [DocumentSupport]. Pure `java.io.File` / `java.util.zip` / `org.xmlpull.v1` — no
 * Android runtime.
 * References:
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 * - `XmlPullParser` — https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 */
class DocumentSupportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writeZip(name: String, entries: Map<String, ByteArray>): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    // --- ZIP ---------------------------------------------------------------------------------

    @Test
    fun readZipEntryTextReturnsPresentEntry() {
        val zip = writeZip("a.zip", mapOf("word/document.xml" to bytesOf("<w:document/>")))
        assertEquals("<w:document/>", DocumentSupport.readZipEntryText(zip, "word/document.xml"))
    }

    @Test
    fun readZipEntryTextReturnsNullForMissingEntry() {
        val zip = writeZip("b.zip", mapOf("only.txt" to bytesOf("x")))
        assertNull(DocumentSupport.readZipEntryText(zip, "absent.txt"))
    }

    @Test
    fun readZipEntryTextReturnsNullForUnreadableArchive() {
        val notAZip = temp.newFile("broken.zip").apply { writeBytes(bytesOf("not a zip")) }
        assertNull(DocumentSupport.readZipEntryText(notAZip, "any"))
    }

    @Test
    fun readZipEntryTextDecodesUtf8() {
        val zip = writeZip("c.zip", mapOf("t.txt" to bytesOf("Привет")))
        assertEquals("Привет", DocumentSupport.readZipEntryText(zip, "t.txt"))
    }

    @Test
    fun zipEntryNamesListsEntries() {
        val zip = writeZip(
            "d.zip",
            mapOf(
                "mimetype" to bytesOf("m"),
                "content.xml" to bytesOf("<x/>"),
                "meta.xml" to bytesOf("<y/>")
            )
        )
        assertEquals(listOf("mimetype", "content.xml", "meta.xml"), DocumentSupport.zipEntryNames(zip))
    }

    @Test
    fun zipEntryNamesReturnsEmptyForUnreadableArchive() {
        val notAZip = temp.newFile("broken2.zip").apply { writeBytes(bytesOf("nope")) }
        assertEquals(emptyList<String>(), DocumentSupport.zipEntryNames(notAZip))
    }

    // --- XML ---------------------------------------------------------------------------------

    private val sampleXml = "<root><a>Hello</a><w:p>World</w:p><a>!</a></root>"

    @Test
    fun xmlTextContentWithoutTagConcatenatesAllText() {
        assertEquals("HelloWorld!", DocumentSupport.xmlTextContent(sampleXml))
    }

    @Test
    fun xmlTextContentWithTagFiltersByLocalName() {
        assertEquals("Hello!", DocumentSupport.xmlTextContent(sampleXml, "a"))
    }

    @Test
    fun xmlTextContentIgnoresNamespacePrefix() {
        assertEquals("World", DocumentSupport.xmlTextContent(sampleXml, "p"))
    }

    @Test
    fun xmlTextContentDecodesEntities() {
        assertEquals(
            "A & B < C",
            DocumentSupport.xmlTextContent("<a>A &amp; B &lt; C</a>", "a")
        )
    }

    @Test
    fun xmlTextContentReturnsEmptyForBlankInput() {
        assertEquals("", DocumentSupport.xmlTextContent("  "))
    }

    @Test
    fun countXmlElementsCountsLocalName() {
        val xml = "<r><w:p/><w:p><text/></w:p><dp>x</dp></r>"
        assertEquals(2, DocumentSupport.countXmlElements(xml, "p"))
        assertEquals(1, DocumentSupport.countXmlElements(xml, "text"))
        assertEquals(0, DocumentSupport.countXmlElements(xml, "absent"))
    }

    @Test
    fun countXmlElementsReturnsZeroForBlankInput() {
        assertEquals(0, DocumentSupport.countXmlElements("", "p"))
    }

    @Test
    fun xmlAttributeValuesCollectsMatchingAttributes() {
        val xml = """<workbook>
            <sheets>
                <sheet name="Лист1" sheetId="1"/>
                <sheet name="Sheet2" sheetId="2"/>
            </sheets>
        </workbook>"""
        assertEquals(listOf("Лист1", "Sheet2"), DocumentSupport.xmlAttributeValues(xml, "sheet", "name"))
    }

    // --- byte/text formatting ----------------------------------------------------------------

    @Test
    fun formatBytesUsesRussianUnits() {
        assertEquals("0 Б", DocumentSupport.formatBytes(0))
        assertEquals("512 Б", DocumentSupport.formatBytes(512))
        assertEquals("1.0 КБ", DocumentSupport.formatBytes(1024))
        assertEquals("1.0 МБ", DocumentSupport.formatBytes(1024L * 1024L))
        assertEquals("1.0 ГБ", DocumentSupport.formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun readFileTextStripsUtf8Bom() {
        val file = temp.newFile("utf8.txt").apply {
            writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytesOf("Привет"))
        }
        assertEquals("Привет", DocumentSupport.readFileText(file))
    }

    @Test
    fun readFileTextStripsUtf16LeBom() {
        val file = temp.newFile("le.txt").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Привет".toByteArray(Charsets.UTF_16LE))
        }
        assertEquals("Привет", DocumentSupport.readFileText(file))
    }

    @Test
    fun readFileTextStripsUtf16BeBom() {
        val file = temp.newFile("be.txt").apply {
            writeBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Привет".toByteArray(Charsets.UTF_16BE))
        }
        assertEquals("Привет", DocumentSupport.readFileText(file))
    }

    @Test
    fun readFileTextWithoutBomIsUtf8() {
        val file = temp.newFile("plain.txt").apply { writeBytes(bytesOf("простой текст")) }
        assertEquals("простой текст", DocumentSupport.readFileText(file))
    }

    // --- DOCX --------------------------------------------------------------------------------

    private val docxXml = """<w:document xmlns:w="w">
        <w:body>
            <w:p><w:r><w:t>Заголовок</w:t></w:r></w:p>
            <w:p><w:r><w:t>При</w:t></w:r><w:r><w:t>вет</w:t></w:r><w:r><w:tab/><w:t>мир</w:t></w:r></w:p>
            <w:p/>
        </w:body>
    </w:document>"""

    @Test
    fun docxTextJoinsRunsAndBreaksParagraphs() {
        assertEquals("Заголовок\nПривет\tмир", DocumentSupport.docxText(docxXml))
    }

    @Test
    fun docxTextWorksWithoutNamespacePrefixes() {
        assertEquals("a\nb", DocumentSupport.docxText("<document><p><t>a</t></p><p><t>b</t></p></document>"))
    }

    @Test
    fun extractRunTextReturnsEmptyForBlankInput() {
        assertEquals("", DocumentSupport.extractRunText("", "t", "p"))
    }

    @Test
    fun docxTextFromZipEntry() {
        val zip = writeZip(
            "doc.docx",
            mapOf("word/document.xml" to bytesOf(docxXml), "[Content_Types].xml" to bytesOf("<x/>"))
        )
        val xml = DocumentSupport.readZipEntryText(zip, "word/document.xml")
        assertEquals("Заголовок\nПривет\tмир", DocumentSupport.docxText(xml!!))
    }

    // --- XLSX --------------------------------------------------------------------------------

    @Test
    fun xlsxSheetNamesReadsWorkbookOrder() {
        val workbook = """<workbook><sheets>
            <sheet name="Лист1" sheetId="1" r:id="rId1"/>
            <sheet name="Данные" sheetId="2" r:id="rId2"/>
        </sheets></workbook>"""
        assertEquals(listOf("Лист1", "Данные"), DocumentSupport.xlsxSheetNames(workbook))
    }

    @Test
    fun xlsxSharedStringsJoinsRunsAndSkipsPhonetics() {
        val xml = """<sst>
            <si><t>Простой</t></si>
            <si><r><t>Сос</t></r><r><t>тав</t></r><rPh><t>phonetic</t></rPh></si>
        </sst>"""
        assertEquals(listOf("Простой", "Состав"), DocumentSupport.xlsxSharedStrings(xml))
    }

    @Test
    fun xlsxSheetTextResolvesSharedInlineAndGappedCells() {
        val sheet = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2"><v>42</v></c><c r="C2" t="inlineStr"><is><t>привет</t></is></c></row>
            <row r="3"><c r="A3" t="b"><v>1</v></c></row>
        </sheetData></worksheet>"""
        val expected = "Имя\tВозраст\n42\t\tпривет\nTRUE"
        assertEquals(expected, DocumentSupport.xlsxSheetText(sheet, listOf("Имя", "Возраст")))
    }

    @Test
    fun xlsxSheetTextFromZipEntry() {
        val zip = writeZip(
            "book.xlsx",
            mapOf(
                "xl/workbook.xml" to bytesOf("<workbook><sheets><sheet name=\"S\" r:id=\"rId1\"/></sheets></workbook>"),
                "xl/sharedStrings.xml" to bytesOf("<sst><si><t>A</t></si><si><t>B</t></si></sst>"),
                "xl/worksheets/sheet1.xml" to bytesOf(
                    "<worksheet><sheetData><row><c r=\"A1\" t=\"s\"><v>0</v></c>" +
                        "<c r=\"B1\" t=\"s\"><v>1</v></c></row></sheetData></worksheet>"
                ),
                "[Content_Types].xml" to bytesOf("<x/>")
            )
        )
        val shared = DocumentSupport.readZipEntryText(zip, "xl/sharedStrings.xml")!!
        val sheet = DocumentSupport.readZipEntryText(zip, "xl/worksheets/sheet1.xml")!!
        assertEquals("A\tB", DocumentSupport.xlsxSheetText(sheet, DocumentSupport.xlsxSharedStrings(shared)))
    }

    @Test
    fun xlsxCellValueReadsNumericInlineAndSharedCells() {
        val sheet = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>текст</t></is></c></row>
            <row r="2"><c r="A2"><v>42</v></c></row>
        </sheetData></worksheet>"""
        val shared = listOf("Имя")
        assertEquals("Имя", DocumentSupport.xlsxCellValue(sheet, shared, "A1"))
        assertEquals("текст", DocumentSupport.xlsxCellValue(sheet, shared, "B1"))
        assertEquals("42", DocumentSupport.xlsxCellValue(sheet, shared, "a2"))
    }

    @Test
    fun xlsxCellValueReturnsNullForAbsentCellOrBlankSheet() {
        val sheet = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>"
        assertNull(DocumentSupport.xlsxCellValue(sheet, emptyList(), "C3"))
        assertNull(DocumentSupport.xlsxCellValue("", emptyList(), "A1"))
    }

    // --- OpenDocument ------------------------------------------------------------------------
    @Test
    fun odfTextBreaksParagraphsAndExpandsTabs() {
        val xml = """<office:body><office:text>
            <text:p>Привет</text:p>
            <text:p>Мир<text:tab/>!</text:p>
            <text:p>a<text:s text:c="3"/>b</text:p>
        </office:text></office:body>"""
        assertEquals("Привет\nМир\t!\na   b", DocumentSupport.odfText(xml))
    }

    private val odsXml = """<office:body><office:spreadsheet>
        <table:table table:name="Первый">
            <table:table-row>
                <table:table-cell><text:p>A1</text:p></table:table-cell>
                <table:table-cell><text:p>B1</text:p></table:table-cell>
            </table:table-row>
        </table:table>
        <table:table table:name="Второй">
            <table:table-row><table:table-cell><text:p>X</text:p></table:table-cell></table:table-row>
        </table:table>
    </office:spreadsheet></office:body>"""

    @Test
    fun odsSheetNamesListsTablesInOrder() {
        assertEquals(listOf("Первый", "Второй"), DocumentSupport.odsSheetNames(odsXml))
    }

    @Test
    fun odsSheetTextSelectsByNameAndIndex() {
        assertEquals("A1\tB1", DocumentSupport.odsSheetText(odsXml, null))
        assertEquals("A1\tB1", DocumentSupport.odsSheetText(odsXml, "первый"))
        assertEquals("X", DocumentSupport.odsSheetText(odsXml, "Второй"))
        assertEquals("X", DocumentSupport.odsSheetText(odsXml, "2"))
    }

    @Test
    fun odsSheetTextReturnsNullForUnknownSheet() {
        assertNull(DocumentSupport.odsSheetText(odsXml, "нет такого"))
        assertNull(DocumentSupport.odsSheetText(odsXml, "9"))
    }

    // --- PPTX --------------------------------------------------------------------------------

    @Test
    fun sortedSlideEntriesIsNumericNotLexical() {
        val names = listOf(
            "ppt/slides/slide10.xml",
            "ppt/media/image1.png",
            "ppt/slides/slide2.xml",
            "ppt/slides/slide1.xml"
        )
        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide10.xml"),
            DocumentSupport.sortedSlideEntries(names)
        )
    }

    @Test
    fun extractRunTextReadsSlideTextRuns() {
        val slide = """<p:sld><p:cSld><p:spTree><p:sp><p:txBody>
            <a:p><a:r><a:t>Слайд</a:t></a:r></a:p>
            <a:p><a:r><a:t>один</a:t></a:r></a:p>
        </p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
        assertEquals("Слайд\nодин", DocumentSupport.extractRunText(slide, "t", "p"))
    }
}
