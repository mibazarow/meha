package com.andharness.ru.data.tools.documents

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [DocumentTextExtractor] (update.md idea #4): the RAG indexer must read the same
 * formats `doc_read` does, cap huge files honestly and refuse unknown/binary containers.
 */
class DocumentTextExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun plainTextIsExtracted() {
        val file = temp.newFile("note.txt").apply { writeText("Договор действует тридцать дней.") }
        val result = DocumentTextExtractor.extract(file)
        assertTrue(result is DocumentTextExtractor.Extracted.Success)
        val success = result as DocumentTextExtractor.Extracted.Success
        assertEquals(DocumentFormat.TEXT, success.format)
        assertTrue(success.text.contains("тридцать дней"))
        assertFalse(success.truncated)
    }

    @Test
    fun textOverTheCapIsTruncatedAndFlagged() {
        val file = temp.newFile("long.txt").apply { writeText("x".repeat(500)) }
        val result = DocumentTextExtractor.extract(file, maxChars = 100)
        val success = result as DocumentTextExtractor.Extracted.Success
        assertTrue(success.truncated)
        assertEquals(100, success.text.length)
    }

    @Test
    fun zippedDocxIsExtracted() {
        val file = temp.newFile("contract.docx")
        writeDocx(file, "Срок действия договора — 30 дней.")
        val result = DocumentTextExtractor.extract(file)
        val success = result as DocumentTextExtractor.Extracted.Success
        assertEquals(DocumentFormat.DOCX, success.format)
        assertTrue(success.text.contains("30 дней"))
    }

    /**
     * PdfBox (Android port) resolves its standard-14 AFM metrics through Android *assets*, which a
     * bare JVM unit test does not have — so PDF *extraction* is exercised on-device by the RAG smoke,
     * while the JVM verifies that a PDF is recognised and marked indexable.
     */
    @Test
    fun pdfIsRecognisedAndIndexable() {
        val file = temp.newFile("contract.pdf")
        file.writeBytes("%PDF-1.4\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        assertEquals(DocumentFormat.PDF, DocumentFormatDetector.detect(file))
        assertTrue(DocumentTextExtractor.isIndexable(DocumentFormat.PDF))
    }

    @Test
    fun csvBecomesTabSeparatedRows() {
        val file = temp.newFile("table.csv").apply { writeText("name,term\nalpha,30\n") }
        val success = DocumentTextExtractor.extract(file) as DocumentTextExtractor.Extracted.Success
        assertTrue(success.text.contains("name\tterm"))
        assertTrue(success.text.contains("alpha\t30"))
    }

    @Test
    fun aFileThatIsNotReallyPdfIsAnHonestFailure() {
        val file = temp.newFile("fake.pdf").apply { writeText("this is not a pdf at all") }
        val result = DocumentTextExtractor.extract(file)
        assertTrue(result is DocumentTextExtractor.Extracted.Failure)
        assertEquals(DocumentFormat.BINARY_UNKNOWN, (result as DocumentTextExtractor.Extracted.Failure).format)
        assertFalse(DocumentTextExtractor.isIndexable(result.format))
    }

    /**
     * A `.docx` whose `word/document.xml` is present but not well-formed XML must not throw out of
     * `extract` (previously `XmlPullParserException` escaped to the caller); it is an honest Failure
     * with a reason so the indexer can report the file instead of losing it as an anonymous failure.
     */
    @Test
    fun corruptDocxXmlIsAnHonestFailureNotAThrow() {
        val file = temp.newFile("broken.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            // A bare `&` is invalid XML, so the pull parser raises while stripping the document.
            zip.write(
                "<w:document><w:p><w:t>bad & broken</w:t></w:p></w:document>".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        val result = DocumentTextExtractor.extract(file)
        assertTrue(result is DocumentTextExtractor.Extracted.Failure)
        val failure = result as DocumentTextExtractor.Extracted.Failure
        assertEquals(DocumentFormat.DOCX, failure.format)
        assertTrue(failure.message.isNotBlank())
    }

    /** A malformed delimited file (unterminated quote) must be an honest Failure, never a throw. */
    @Test
    fun malformedCsvIsAnHonestFailureNotAThrow() {
        val file = temp.newFile("broken.csv").apply { writeText("name,note\n\"unterminated\n") }
        val result = DocumentTextExtractor.extract(file)
        assertTrue(result is DocumentTextExtractor.Extracted.Failure)
        assertEquals(DocumentFormat.CSV, (result as DocumentTextExtractor.Extracted.Failure).format)
    }

    /** An empty file is readable and yields a blank (non-throwing) Success; the indexer skips it. */
    @Test
    fun emptyFileIsABlankSuccess() {
        val file = temp.newFile("empty.txt").apply { writeText("") }
        val result = DocumentTextExtractor.extract(file)
        assertTrue(result is DocumentTextExtractor.Extracted.Success)
        val success = result as DocumentTextExtractor.Extracted.Success
        assertEquals(DocumentFormat.TEXT, success.format)
        assertTrue(success.text.isBlank())
    }

    private fun writeDocx(target: File, text: String) {
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body>" +
                "<w:p><w:r><w:t>$text</w:t></w:r></w:p>" +
                "</w:body></w:document>"
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
