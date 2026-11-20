package com.andharness.ru.data.index

import com.andharness.ru.data.tools.documents.DocumentFormat
import com.andharness.ru.data.tools.documents.DocumentTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DocumentExtraction]: the classification the document indexer relies on to
 * report *skipped* (nothing to index) separately from *failed* (a genuine error with a reason) — so a
 * bad file is never lost behind an anonymous `failed` count.
 */
class DocumentExtractionTest {

    @Test
    fun blankSuccessIsSkipped() {
        val extracted = DocumentTextExtractor.Extracted.Success(DocumentFormat.TEXT, "   \n\t ", false)
        val outcome = DocumentExtraction.classify(DocumentFormat.TEXT, extracted)
        assertTrue(outcome is DocumentExtraction.Skipped)
        assertEquals("no extractable text", (outcome as DocumentExtraction.Skipped).reason)
    }

    @Test
    fun nonBlankSuccessIsTextAndTrimmed() {
        val extracted = DocumentTextExtractor.Extracted.Success(DocumentFormat.TEXT, "  привет  ", false)
        val outcome = DocumentExtraction.classify(DocumentFormat.TEXT, extracted)
        assertEquals(DocumentExtraction.Text("привет", ocrUsed = false, pages = 0), outcome)
    }

    @Test
    fun failureOnIndexableFormatIsFailedWithReason() {
        val extracted = DocumentTextExtractor.Extracted.Failure(
            DocumentFormat.DOCX,
            "org.xmlpull.v1.XmlPullParserException: broken",
        )
        val outcome = DocumentExtraction.classify(DocumentFormat.DOCX, extracted)
        assertTrue(outcome is DocumentExtraction.Failed)
        assertEquals(
            "org.xmlpull.v1.XmlPullParserException: broken",
            (outcome as DocumentExtraction.Failed).reason,
        )
    }

    @Test
    fun failureOnUnsupportedFormatIsSkippedNotFailed() {
        val extracted = DocumentTextExtractor.Extracted.Failure(
            DocumentFormat.BINARY_UNKNOWN,
            "Формат BINARY_UNKNOWN не поддерживается для извлечения текста.",
        )
        val outcome = DocumentExtraction.classify(DocumentFormat.BINARY_UNKNOWN, extracted)
        assertTrue(outcome is DocumentExtraction.Skipped)
        assertEquals("unsupported format BINARY_UNKNOWN", (outcome as DocumentExtraction.Skipped).reason)
    }

    @Test
    fun failedDescribesThrowableWithTypeAndMessage() {
        val outcome = DocumentExtraction.failed(IllegalStateException("boom"))
        assertEquals("java.lang.IllegalStateException: boom", outcome.reason)
    }

    @Test
    fun failedFallsBackToTypeWhenMessageIsBlank() {
        val outcome = DocumentExtraction.failed(RuntimeException())
        assertEquals("java.lang.RuntimeException", outcome.reason)
    }
}
