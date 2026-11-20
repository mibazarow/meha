package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

/**
 * JVM tests for the pure conversion mapping in [OfficeConvert].
 *
 * References:
 * - OOXML / OpenDocument packages — https://learn.microsoft.com/en-us/office/open-xml/,
 *   https://docs.oasis-open.org/office/v1.2/os/OpenDocument-v1.2-os-part3.html
 */
class OfficeConvertTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sourceKindClassifiesFormats() {
        assertEquals(OfficeConvert.SourceKind.TEXT, OfficeConvert.sourceKind(DocumentFormat.DOCX))
        assertEquals(OfficeConvert.SourceKind.TEXT, OfficeConvert.sourceKind(DocumentFormat.MARKDOWN))
        assertEquals(OfficeConvert.SourceKind.TABLE, OfficeConvert.sourceKind(DocumentFormat.XLSX))
        assertEquals(OfficeConvert.SourceKind.TABLE, OfficeConvert.sourceKind(DocumentFormat.TSV))
        assertEquals(OfficeConvert.SourceKind.PRESENTATION, OfficeConvert.sourceKind(DocumentFormat.PPTX))
        assertEquals(OfficeConvert.SourceKind.PRESENTATION, OfficeConvert.sourceKind(DocumentFormat.ODP))
        assertEquals(null, OfficeConvert.sourceKind(DocumentFormat.PDF))
        assertEquals(null, OfficeConvert.sourceKind(DocumentFormat.ZIP_UNKNOWN))
    }

    @Test
    fun supportsMatrixCoversExpectedTargets() {
        assertTrue(OfficeConvert.supports(OfficeConvert.Target.MD, OfficeConvert.SourceKind.TEXT))
        assertTrue(OfficeConvert.supports(OfficeConvert.Target.DOCX, OfficeConvert.SourceKind.TEXT))
        assertFalse(OfficeConvert.supports(OfficeConvert.Target.XLSX, OfficeConvert.SourceKind.TEXT))
        assertTrue(OfficeConvert.supports(OfficeConvert.Target.JSON, OfficeConvert.SourceKind.TABLE))
        assertTrue(OfficeConvert.supports(OfficeConvert.Target.ODS, OfficeConvert.SourceKind.TABLE))
        assertFalse(OfficeConvert.supports(OfficeConvert.Target.PPTX, OfficeConvert.SourceKind.PRESENTATION))
        assertTrue(OfficeConvert.supports(OfficeConvert.Target.MD, OfficeConvert.SourceKind.PRESENTATION))
    }

    @Test
    fun targetFromAcceptsNameAndExtension() {
        assertEquals(OfficeConvert.Target.MD, OfficeConvert.Target.from("md"))
        assertEquals(OfficeConvert.Target.MD, OfficeConvert.Target.from(".markdown"))
        assertEquals(OfficeConvert.Target.CSV, OfficeConvert.Target.from("CSV"))
        assertEquals(null, OfficeConvert.Target.from("pdf"))
        assertEquals(null, OfficeConvert.Target.from(null))
    }

    @Test
    fun convertTextToPlainAndRichFormats() {
        val text = "Строка 1\nСтрока 2"
        assertEquals(
            text,
            OfficeConvert.convert(OfficeConvert.Target.TXT, OfficeConvert.SourceKind.TEXT, text, emptyList())
                .toString(Charsets.UTF_8)
        )
        val docx = OfficeConvert.convert(OfficeConvert.Target.DOCX, OfficeConvert.SourceKind.TEXT, text, emptyList())
        val file = temp.newFile("a.docx").apply { writeBytes(docx) }
        val hasDocument = ZipFile(file).use { zip ->
            zip.entries().asSequence().any { it.name == "word/document.xml" }
        }
        assertTrue(hasDocument)
    }

    @Test
    fun convertTableToMarkdownAndJson() {
        val rows = listOf(listOf("Имя", "Возраст"), listOf("Аня", "30"))
        val md = OfficeConvert.convert(OfficeConvert.Target.MD, OfficeConvert.SourceKind.TABLE, "", rows)
            .toString(Charsets.UTF_8)
        assertTrue(md.startsWith("| Имя | Возраст |"))
        assertTrue(md.contains("| --- | --- |"))
        assertTrue(md.contains("| Аня | 30 |"))

        val json = OfficeConvert.rowsToJson(rows)
        assertEquals("[[\"Имя\",\"Возраст\"],[\"Аня\",\"30\"]]", json)

        val csv = OfficeConvert.convert(OfficeConvert.Target.CSV, OfficeConvert.SourceKind.TABLE, "", rows)
            .toString(Charsets.UTF_8)
        assertTrue(csv.contains("Имя,Возраст"))
    }

    @Test
    fun convertRefusesUnsupportedCombination() {
        val error = runCatching {
            OfficeConvert.convert(OfficeConvert.Target.XLSX, OfficeConvert.SourceKind.TEXT, "x", emptyList())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
