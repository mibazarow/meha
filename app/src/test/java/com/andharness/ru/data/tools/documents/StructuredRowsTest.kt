package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * JVM tests for the structured row extraction backing `doc_tables`: [XlsxTextExtractor.xlsxSheetRows]
 * and [OdfTextExtractor.odsSheetRows], plus the delimited reader.
 */
class StructuredRowsTest {

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

    @Test
    fun xlsxSheetRowsReturnsStructuredCells() {
        val bytes = DocumentWriter.xlsx(listOf(listOf("Имя", "Возраст"), listOf("Аня", "30")))
        val sheet = entryText(bytes, "xl/worksheets/sheet1.xml")
        assertEquals(
            listOf(listOf("Имя", "Возраст"), listOf("Аня", "30")),
            DocumentSupport.xlsxSheetRows(sheet, emptyList())
        )
    }

    @Test
    fun xlsxSheetRowsKeepsColumnPositionsWithGaps() {
        val bytes = DocumentWriter.xlsx(listOf(listOf("a", "b"), listOf("c", "d")))
        var sheet = entryText(bytes, "xl/worksheets/sheet1.xml")
        sheet = DocumentWriter.setXlsxCell(sheet, "C1", "e")
        assertEquals(
            listOf(listOf("a", "b", "e"), listOf("c", "d")),
            DocumentSupport.xlsxSheetRows(sheet, emptyList())
        )
    }

    @Test
    fun xlsxSheetRowsEmptyForBlank() {
        assertEquals(emptyList<List<String>>(), DocumentSupport.xlsxSheetRows("", emptyList()))
    }

    @Test
    fun odsSheetRowsReturnsStructuredCells() {
        val bytes = DocumentWriter.ods(listOf(listOf("x", "y"), listOf("1", "2")), sheetName = "Лист")
        val content = entryText(bytes, "content.xml")
        assertEquals(listOf("Лист"), DocumentSupport.odsSheetNames(content))
        assertEquals(
            listOf(listOf("x", "y"), listOf("1", "2")),
            DocumentSupport.odsSheetRows(content, "Лист")
        )
        assertEquals(null, DocumentSupport.odsSheetRows(content, "Нет такого"))
    }

    @Test
    fun delimitedRowsReadCsv() {
        val file = temp.newFile("t.csv").apply { writeText("a,b\nc,d\n", Charsets.UTF_8) }
        val inspector = DocumentInspector()
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), inspector.extractDelimitedRows(file, ','))
    }
}
