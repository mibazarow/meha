package com.andharness.ru.data.index

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DocumentTypes]: the document indexer must accept exactly the formats whose text
 * can be extracted (by MIME type or by filename extension) and reject everything else.
 */
class DocumentTypesTest {

    @Test
    fun commonDocumentMimeTypesAreAccepted() {
        assertTrue(DocumentTypes.acceptsMimeType("application/pdf"))
        assertTrue(DocumentTypes.acceptsMimeType("text/plain"))
        assertTrue(
            DocumentTypes.acceptsMimeType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
        assertTrue(
            DocumentTypes.acceptsMimeType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
    }

    @Test
    fun mimeTypeMatchingIsTrimmedAndCaseInsensitive() {
        assertTrue(DocumentTypes.acceptsMimeType("  APPLICATION/PDF  "))
    }

    @Test
    fun unknownMimeTypeIsRejected() {
        assertFalse(DocumentTypes.acceptsMimeType("image/jpeg"))
        assertFalse(DocumentTypes.acceptsMimeType("video/mp4"))
        assertFalse(DocumentTypes.acceptsMimeType(null))
        assertFalse(DocumentTypes.acceptsMimeType(""))
    }

    @Test
    fun documentExtensionsAreAcceptedCaseInsensitively() {
        assertTrue(DocumentTypes.acceptsExtension("Report.PDF"))
        assertTrue(DocumentTypes.acceptsExtension("notes.md"))
        assertTrue(DocumentTypes.acceptsExtension("budget.xlsx"))
        assertTrue(DocumentTypes.acceptsExtension("contract.docx"))
    }

    @Test
    fun nonDocumentExtensionsAreRejected() {
        assertFalse(DocumentTypes.acceptsExtension("photo.jpg"))
        assertFalse(DocumentTypes.acceptsExtension("archive.zip"))
        assertFalse(DocumentTypes.acceptsExtension("noextension"))
        assertFalse(DocumentTypes.acceptsExtension(null))
    }

    @Test
    fun acceptsWhenEitherMimeOrExtensionMatches() {
        // Blank MIME but a document extension → still accepted (some providers omit the type).
        assertTrue(DocumentTypes.accepts(mimeType = "", displayName = "readme.md"))
        // Known MIME with an odd/absent extension → accepted.
        assertTrue(DocumentTypes.accepts(mimeType = "application/pdf", displayName = "scan"))
        // Neither matches → rejected.
        assertFalse(DocumentTypes.accepts(mimeType = "image/png", displayName = "shot.png"))
    }
}
