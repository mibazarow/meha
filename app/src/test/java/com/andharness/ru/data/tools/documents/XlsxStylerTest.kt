package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * JVM tests for [XlsxStyler] and [XlsxWriter.setXlsxCellStyle]: the rewritten worksheet and stylesheet
 * must stay well-formed, keep every cell value, and actually gain the new `<xf>`/`s=` references.
 *
 * References:
 * - SpreadsheetML styles — https://learn.microsoft.com/en-us/office/open-xml/spreadsheet/spreadsheetml
 */
class XlsxStylerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entryText(bytes: ByteArray, entry: String): String {
        val probe = File.createTempFile("probe", ".zip", temp.root).apply { writeBytes(bytes) }
        ZipFile(probe).use { zip ->
            val zipEntry = zip.getEntry(entry)
            assertNotNull("ZIP must contain $entry", zipEntry)
            return zip.getInputStream(zipEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    private fun assertWellFormed(xml: String) {
        val parser = try {
            XmlPullParserFactory.newInstance().newPullParser()
        } catch (t: Throwable) {
            @Suppress("UNCHECKED_CAST")
            Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
        }
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) event = parser.next()
    }

    private fun sample(): Pair<String, String> {
        val bytes = DocumentWriter.xlsx(listOf(listOf("a", "b"), listOf("c", "d")))
        return entryText(bytes, "xl/worksheets/sheet1.xml") to entryText(bytes, "xl/styles.xml")
    }

    @Test
    fun parseRangeExpandsBlocksAndRejectsBadInput() {
        assertEquals(listOf("A1"), XlsxStyler.parseRange("A1"))
        val block = XlsxStyler.parseRange("A1:B2")!!
        assertEquals(listOf("A1", "B1", "A2", "B2"), block)
        assertEquals(listOf("A1", "B1"), XlsxStyler.parseRange("A1:B1, A2").let { it!!.take(2) })
        assertNull(XlsxStyler.parseRange("2B"))
        assertNull(XlsxStyler.parseRange("A0"))
    }

    @Test
    fun styleAddsFontFillAndNumberFormat() {
        val (sheet, styles) = sample()
        val result = XlsxStyler.style(
            sheet, styles, "A1:B1",
            XlsxStyler.Style(bold = true, fillColor = "#FF0000", numberFormat = "0.00")
        )

        assertEquals(2, result.cellCount)
        assertEquals("a fresh cellXf follows the one existing xf", 1, result.styleIndex)
        assertWellFormed(result.sheetXml)
        assertWellFormed(result.stylesXml)

        assertTrue(result.stylesXml.contains("<b/>"))
        assertTrue(result.stylesXml.contains("patternFill patternType=\"solid\""))
        assertTrue(result.stylesXml.contains("rgb=\"FFFF0000\""))
        assertTrue(result.stylesXml.contains("numFmt numFmtId=\"164\""))
        assertTrue(result.stylesXml.contains("<fonts count=\"2\""))
        assertTrue(result.stylesXml.contains("<fills count=\"2\""))
        assertTrue(result.stylesXml.contains("<cellXfs count=\"2\""))
        assertTrue(result.sheetXml.contains("s=\"1\""))

        assertEquals(
            "cell values must be unchanged",
            listOf(listOf("a", "b"), listOf("c", "d")),
            DocumentSupport.xlsxSheetRows(result.sheetXml, emptyList())
        )
    }

    @Test
    fun styleRejectsEmptyStyleAndBadColor() {
        val (sheet, styles) = sample()
        val empty = runCatching { XlsxStyler.style(sheet, styles, "A1", XlsxStyler.Style()) }.exceptionOrNull()
        assertTrue(empty is IllegalArgumentException)
        val badColor = runCatching {
            XlsxStyler.style(sheet, styles, "A1", XlsxStyler.Style(fillColor = "nope"))
        }.exceptionOrNull()
        assertTrue(badColor is IllegalArgumentException)
    }

    @Test
    fun setXlsxCellStyleKeepsValueAndAddsAttribute() {
        val (sheet, _) = sample()
        val updated = XlsxWriter.setXlsxCellStyle(sheet, "A1", 3)
        assertWellFormed(updated)
        assertTrue(updated.contains("s=\"3\""))
        assertEquals("a\tb\nc\td", DocumentSupport.xlsxSheetText(updated, emptyList()))
    }

    @Test
    fun setXlsxCellStyleCreatesMissingCell() {
        val (sheet, _) = sample()
        val updated = XlsxWriter.setXlsxCellStyle(sheet, "D4", 2)
        assertWellFormed(updated)
        assertTrue(updated.contains("r=\"D4\" s=\"2\""))
        assertEquals("a", DocumentSupport.xlsxCellValue(updated, emptyList(), "A1"))
        assertEquals("d", DocumentSupport.xlsxCellValue(updated, emptyList(), "B2"))
    }
}
