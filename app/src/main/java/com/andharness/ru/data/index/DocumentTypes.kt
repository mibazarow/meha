package com.andharness.ru.data.index

import java.util.Locale

/**
 * Pure, Android-free classification of the document types the whole-device document indexer picks up:
 * the MIME types and file extensions of the formats whose text the shared
 * [com.andharness.ru.data.tools.documents.DocumentTextExtractor] can read (PDF, Word, plain text /
 * Markdown, Excel, plus a few container formats that extractor already supports).
 *
 * Kept free of Android so the filter is unit-tested on the JVM. `MediaStore.Files` indexes non-media
 * files (text, HTML, PDF, …) and is the discovery surface; the filter is applied both in the SQL
 * selection and again in Kotlin as a defensive guard against odd provider rows — reference:
 * https://developer.android.com/reference/android/provider/MediaStore.Files
 */
object DocumentTypes {

    /**
     * MIME types of the indexed formats (IANA / Android MediaStore values). `text/plain` also covers
     * files whose name has an indexed extension but for which the provider reported no finer type.
     */
    val MIME_TYPES: Set<String> = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/rtf",
        "text/rtf",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
        "text/csv",
    )

    /** Extensions of the indexed formats, matched case-insensitively as a filename suffix. */
    val EXTENSIONS: Set<String> = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "txt", "md", "markdown", "csv", "rtf", "odt", "ods",
    )

    /** True when [mimeType] (case-insensitive, trimmed) is one of the indexed document MIME types. */
    fun acceptsMimeType(mimeType: String?): Boolean {
        val normalized = mimeType?.trim()?.lowercase(Locale.ROOT) ?: return false
        return normalized in MIME_TYPES
    }

    /** True when [displayName] ends with one of the indexed document extensions. */
    fun acceptsExtension(displayName: String?): Boolean {
        val name = displayName?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (name.isEmpty()) return false
        return EXTENSIONS.any { name.endsWith(".$it") }
    }

    /** True when the entry looks like an indexable document by MIME type OR by file extension. */
    fun accepts(mimeType: String?, displayName: String?): Boolean =
        acceptsMimeType(mimeType) || acceptsExtension(displayName)
}
