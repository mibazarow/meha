package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for [DocumentFormatDetector]. Pure `java.io.File` / `java.util.zip` — no Android runtime.
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
class DocumentFormatDetectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private fun textFile(name: String, content: String): File =
        file(name, content.toByteArray(Charsets.UTF_8))

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

    private fun ole2Bytes(): ByteArray = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    ) + ByteArray(64)

    private val pdfBytes = "%PDF-1.7\n%%EOF\n".toByteArray(Charsets.US_ASCII)
    private val rtfBytes = "{\\rtf1\\ansi hello}".toByteArray(Charsets.US_ASCII)

    // --- extension + signature -------------------------------------------------------------

    @Test
    fun pdfExtensionWithPdfSignatureIsPdf() {
        assertEquals(DocumentFormat.PDF, DocumentFormatDetector.detect(file("report.pdf", pdfBytes)))
    }

    @Test
    fun pdfExtensionWithoutSignatureIsBinaryUnknown() {
        assertEquals(
            DocumentFormat.BINARY_UNKNOWN,
            DocumentFormatDetector.detect(textFile("fake.pdf", "not a pdf at all"))
        )
    }

    @Test
    fun rtfExtensionWithSignatureIsRtf() {
        assertEquals(DocumentFormat.RTF, DocumentFormatDetector.detect(file("note.rtf", rtfBytes)))
    }

    @Test
    fun rtfExtensionWithoutSignatureIsBinaryUnknown() {
        assertEquals(
            DocumentFormat.BINARY_UNKNOWN,
            DocumentFormatDetector.detect(textFile("fake.rtf", "plain text"))
        )
    }

    @Test
    fun docxExtensionWithoutZipSignatureIsBinaryUnknown() {
        assertEquals(
            DocumentFormat.BINARY_UNKNOWN,
            DocumentFormatDetector.detect(textFile("fake.docx", "still text"))
        )
    }

    @Test
    fun uppercasedExtensionIsRecognised() {
        assertEquals(DocumentFormat.PDF, DocumentFormatDetector.detect(file("REPORT.PDF", pdfBytes)))
    }

    // --- magic detection with no extension -------------------------------------------------

    @Test
    fun missingExtensionFallsBackToPdfMagic() {
        assertEquals(DocumentFormat.PDF, DocumentFormatDetector.detect(file("mystery", pdfBytes)))
    }

    @Test
    fun missingExtensionFallsBackToRtfMagic() {
        assertEquals(DocumentFormat.RTF, DocumentFormatDetector.detect(file("mystery", rtfBytes)))
    }

    // --- ZIP containers --------------------------------------------------------------------

    @Test
    fun docxExtensionWithZipContentIsDocx() {
        val docx = writeZip(
            "report.docx",
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to "<w:document/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.DOCX, DocumentFormatDetector.detect(docx))
    }

    @Test
    fun docxByContentWhenExtensionUnknown() {
        val zip = writeZip(
            "noextension",
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to "<w:document/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.DOCX, DocumentFormatDetector.detect(zip))
    }

    @Test
    fun xlsxExtensionWithZipContentIsXlsx() {
        val xlsx = writeZip(
            "sheet.xlsx",
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "xl/workbook.xml" to "<workbook/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.XLSX, DocumentFormatDetector.detect(xlsx))
    }

    @Test
    fun xlsxByContentWhenExtensionUnknown() {
        val zip = writeZip(
            "noextension",
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "xl/workbook.xml" to "<workbook/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.XLSX, DocumentFormatDetector.detect(zip))
    }

    @Test
    fun pptxByContentWhenExtensionUnknown() {
        val zip = writeZip(
            "noextension",
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "ppt/presentation.xml" to "<p:presentation/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.PPTX, DocumentFormatDetector.detect(zip))
    }

    @Test
    fun odtIsDetectedViaMimetypeEntry() {
        val odt = writeZip(
            "doc.odt",
            mapOf(
                "mimetype" to "application/vnd.oasis.opendocument.text".toByteArray(),
                "content.xml" to "<office/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.ODT, DocumentFormatDetector.detect(odt))
    }

    @Test
    fun odtByMimetypeWhenExtensionUnknown() {
        val zip = writeZip(
            "noextension",
            mapOf("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray())
        )
        assertEquals(DocumentFormat.ODT, DocumentFormatDetector.detect(zip))
    }

    @Test
    fun odsIsDetectedViaMimetypeEntry() {
        val ods = writeZip(
            "sheet.ods",
            mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet".toByteArray())
        )
        assertEquals(DocumentFormat.ODS, DocumentFormatDetector.detect(ods))
    }

    @Test
    fun odpIsDetectedViaMimetypeEntry() {
        val odp = writeZip(
            "slides.odp",
            mapOf("mimetype" to "application/vnd.oasis.opendocument.presentation".toByteArray())
        )
        assertEquals(DocumentFormat.ODP, DocumentFormatDetector.detect(odp))
    }

    @Test
    fun zipWithoutRecognisableContentIsZipUnknown() {
        val zip = writeZip("plain.zip", mapOf("readme.txt" to "hello".toByteArray()))
        assertEquals(DocumentFormat.ZIP_UNKNOWN, DocumentFormatDetector.detect(zip))
    }

    @Test
    fun zipByMagicWithRecognisableContentIsDetectedWithoutExtension() {
        val zip = writeZip(
            "noextension",
            mapOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "word/document.xml" to "<w:document/>".toByteArray()
            )
        )
        assertEquals(DocumentFormat.DOCX, DocumentFormatDetector.detect(zip))
    }

    // --- legacy OLE2 -----------------------------------------------------------------------

    @Test
    fun legacyDocWithOle2MagicIsLegacyDoc() {
        assertEquals(
            DocumentFormat.LEGACY_DOC,
            DocumentFormatDetector.detect(file("old.doc", ole2Bytes()))
        )
    }

    @Test
    fun legacyXlsWithOle2MagicIsLegacyXls() {
        assertEquals(
            DocumentFormat.LEGACY_XLS,
            DocumentFormatDetector.detect(file("old.xls", ole2Bytes()))
        )
    }

    @Test
    fun legacyPptWithOle2MagicIsLegacyPpt() {
        assertEquals(
            DocumentFormat.LEGACY_PPT,
            DocumentFormatDetector.detect(file("old.ppt", ole2Bytes()))
        )
    }

    @Test
    fun legacyDocWithoutOle2MagicIsBinaryUnknown() {
        assertEquals(
            DocumentFormat.BINARY_UNKNOWN,
            DocumentFormatDetector.detect(textFile("old.doc", "plain text, not OLE2"))
        )
    }

    // --- text vs binary --------------------------------------------------------------------

    @Test
    fun unknownExtensionWithNulByteIsBinaryUnknown() {
        val bytes = byteArrayOf(0x68, 0x65, 0x00, 0x6C, 0x6F)
        assertEquals(DocumentFormat.BINARY_UNKNOWN, DocumentFormatDetector.detect(file("blob", bytes)))
    }

    @Test
    fun unknownExtensionWithPlainTextIsText() {
        assertEquals(DocumentFormat.TEXT, DocumentFormatDetector.detect(textFile("blob", "hello world")))
    }

    @Test
    fun txtIsText() {
        assertEquals(DocumentFormat.TEXT, DocumentFormatDetector.detect(textFile("notes.txt", "hello")))
    }

    @Test
    fun markdownExtensionsAreMarkdown() {
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormatDetector.detect(textFile("readme.md", "# hi")))
        assertEquals(
            DocumentFormat.MARKDOWN,
            DocumentFormatDetector.detect(textFile("readme.markdown", "# hi"))
        )
    }

    @Test
    fun structuredTextExtensionsAreRecognised() {
        assertEquals(DocumentFormat.CSV, DocumentFormatDetector.detect(textFile("data.csv", "a,b\n1,2")))
        assertEquals(DocumentFormat.TSV, DocumentFormatDetector.detect(textFile("data.tsv", "a\tb")))
        assertEquals(DocumentFormat.JSON, DocumentFormatDetector.detect(textFile("data.json", "{}")))
        assertEquals(DocumentFormat.XML, DocumentFormatDetector.detect(textFile("data.xml", "<a/>")))
        assertEquals(DocumentFormat.HTML, DocumentFormatDetector.detect(textFile("page.html", "<html/>")))
        assertEquals(DocumentFormat.HTML, DocumentFormatDetector.detect(textFile("page.htm", "<html/>")))
    }
}
