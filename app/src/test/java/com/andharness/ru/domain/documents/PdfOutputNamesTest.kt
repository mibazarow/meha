package com.andharness.ru.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for PDF output-name derivation and sanitisation. */
class PdfOutputNamesTest {

    @Test
    fun nameComesFromTitleFirst() {
        assertEquals(
            "Мой-отчёт.pdf",
            PdfOutputNames.defaultFileName("Мой отчёт", "Игнорируется", 0L),
        )
    }

    @Test
    fun firstLineIsUsedWhenTitleIsAbsent() {
        assertEquals("Hello-world.pdf", PdfOutputNames.defaultFileName(null, "Hello world", 0L))
        assertEquals("Hello-world.pdf", PdfOutputNames.defaultFileName("   ", "Hello world", 0L))
    }

    @Test
    fun blankSourceFallsBackToATimestampedName() {
        val name = PdfOutputNames.defaultFileName(null, "", 0L)
        assertTrue("неожиданное имя: $name", Regex("""document-\d{8}-\d{6}\.pdf""").matches(name))
    }

    @Test
    fun slugRemovesMarkupAndIllegalCharacters() {
        assertEquals("a-b-c-d-e-f-g-h-i", PdfOutputNames.slug("""a/b:c*d?e"f<g>h|i"""))
        assertEquals("Заголовок", PdfOutputNames.slug("# Заголовок"))
        assertFalse(PdfOutputNames.slug("a/b\\c").contains('/'))
        assertFalse(PdfOutputNames.slug("a/b\\c").contains('\\'))
    }

    @Test
    fun slugIsBounded() {
        assertTrue(PdfOutputNames.slug("x".repeat(500)).length <= PdfOutputNames.MAX_SLUG_LENGTH)
    }

    @Test
    fun extensionIsAddedOnlyWhenMissing() {
        assertEquals("report.pdf", PdfOutputNames.withPdfExtension("report"))
        assertEquals("report.pdf", PdfOutputNames.withPdfExtension("report.pdf"))
        assertEquals("report.PDF", PdfOutputNames.withPdfExtension("report.PDF"))
        assertEquals("", PdfOutputNames.withPdfExtension("  "))
    }

    @Test
    fun existingPdfExtensionIsNotDoubled() {
        assertEquals("Report.pdf", PdfOutputNames.defaultFileName("Report.pdf", null, 0L))
    }
}
