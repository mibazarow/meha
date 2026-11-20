package com.andharness.ru.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM tests for [DocumentFileNames]: extension handling and slug-based DOCX name derivation (no path
 * separators can survive into the name).
 */
class DocumentFileNamesTest {

    @Test
    fun appendsTheExtensionWhenMissing() {
        assertEquals("report.docx", DocumentFileNames.withExtension("report", ".docx"))
    }

    @Test
    fun keepsAnExistingExtensionCaseInsensitively() {
        assertEquals("report.docx", DocumentFileNames.withExtension("report.docx", ".docx"))
        assertEquals("report.DOCX", DocumentFileNames.withExtension("report.DOCX", ".docx"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", DocumentFileNames.withExtension("   ", ".docx"))
    }

    @Test
    fun derivesASafeDocxNameFromArbitraryText() {
        val name = DocumentFileNames.docxFromName("Мой отчёт / 2026")
        assertEquals("Мой-отчёт-2026.docx", name)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(' '))
    }

    @Test
    fun nameWithoutUsableCharactersYieldsEmptyString() {
        assertEquals("", DocumentFileNames.docxFromName("///"))
    }
}
