package com.andharness.ru.data.tools.documents

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Detects a file's [DocumentFormat] from its extension first and its magic signature second.
 * Pure `java.io.File` / `java.util.zip` logic — no Android APIs — so it is unit-testable on the JVM.
 *
 * References:
 * - `java.io.File` — https://developer.android.com/reference/java/io/File
 * - `java.io.FileInputStream` — https://developer.android.com/reference/java/io/FileInputStream
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
object DocumentFormatDetector {

    /** How many leading bytes are inspected for a signature / binary content. */
    private const val HEADER_BYTES = 8 * 1024

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46) // "%PDF"
    private val RTF_MAGIC = byteArrayOf(0x7B, 0x5C, 0x72, 0x74, 0x66) // "{\rtf"
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
    private val OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )

    private const val MIMETYPE_ENTRY = "mimetype"
    private const val CONTENT_TYPES_ENTRY = "[Content_Types].xml"

    private const val ODF_TEXT = "application/vnd.oasis.opendocument.text"
    private const val ODF_SPREADSHEET = "application/vnd.oasis.opendocument.spreadsheet"
    private const val ODF_PRESENTATION = "application/vnd.oasis.opendocument.presentation"

    /** Extension → format for formats fully identified by their extension. */
    private val BY_EXTENSION = mapOf(
        "csv" to DocumentFormat.CSV,
        "tsv" to DocumentFormat.TSV,
        "json" to DocumentFormat.JSON,
        "xml" to DocumentFormat.XML,
        "html" to DocumentFormat.HTML,
        "htm" to DocumentFormat.HTML,
        "md" to DocumentFormat.MARKDOWN,
        "markdown" to DocumentFormat.MARKDOWN,
        "txt" to DocumentFormat.TEXT
    )

    fun detect(file: File): DocumentFormat {
        val extension = file.extension.lowercase(Locale.ROOT)
        val header = readHeader(file, HEADER_BYTES)
        val isPdf = header.startsWith(PDF_MAGIC)
        val isRtf = header.startsWith(RTF_MAGIC)
        val isZip = header.startsWith(ZIP_MAGIC)
        val isOle2 = header.startsWith(OLE2_MAGIC)

        // 1) Trust a known extension, but verify the container signature where one exists.
        when (extension) {
            "pdf" -> return if (isPdf) DocumentFormat.PDF else DocumentFormat.BINARY_UNKNOWN
            "rtf" -> return if (isRtf) DocumentFormat.RTF else DocumentFormat.BINARY_UNKNOWN
            "docx" -> return if (isZip) DocumentFormat.DOCX else DocumentFormat.BINARY_UNKNOWN
            "xlsx" -> return if (isZip) DocumentFormat.XLSX else DocumentFormat.BINARY_UNKNOWN
            "pptx" -> return if (isZip) DocumentFormat.PPTX else DocumentFormat.BINARY_UNKNOWN
            "odt" -> return if (isZip) DocumentFormat.ODT else DocumentFormat.BINARY_UNKNOWN
            "ods" -> return if (isZip) DocumentFormat.ODS else DocumentFormat.BINARY_UNKNOWN
            "odp" -> return if (isZip) DocumentFormat.ODP else DocumentFormat.BINARY_UNKNOWN
            "doc" -> return legacy(isOle2, DocumentFormat.LEGACY_DOC)
            "xls" -> return legacy(isOle2, DocumentFormat.LEGACY_XLS)
            "ppt" -> return legacy(isOle2, DocumentFormat.LEGACY_PPT)
        }
        BY_EXTENSION[extension]?.let { return it }

        // 2) No usable extension: fall back to magic bytes.
        if (isPdf) return DocumentFormat.PDF
        if (isRtf) return DocumentFormat.RTF
        if (isZip) return detectZip(file)
        if (isOle2) return DocumentFormat.BINARY_UNKNOWN

        // 3) Plain content: NUL byte within the header means binary, otherwise text.
        if (header.any { it == 0.toByte() }) return DocumentFormat.BINARY_UNKNOWN
        return DocumentFormat.TEXT
    }

    private fun legacy(isOle2: Boolean, format: DocumentFormat): DocumentFormat =
        if (isOle2) format else DocumentFormat.BINARY_UNKNOWN

    /**
     * Inspects a ZIP container: ODF first (a `mimetype` entry), then OOXML
     * (`[Content_Types].xml` plus a `word/`, `xl/` or `ppt/` part).
     */
    private fun detectZip(file: File): DocumentFormat = try {
        ZipFile(file).use { zip -> inspectZip(zip) }
    } catch (e: IOException) {
        DocumentFormat.ZIP_UNKNOWN
    }

    private fun inspectZip(zip: ZipFile): DocumentFormat {
        val mimetype = zip.getEntry(MIMETYPE_ENTRY)
        if (mimetype != null) {
            val mime = zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.UTF_8) }.trim()
            return when {
                mime.contains(ODF_TEXT) -> DocumentFormat.ODT
                mime.contains(ODF_SPREADSHEET) -> DocumentFormat.ODS
                mime.contains(ODF_PRESENTATION) -> DocumentFormat.ODP
                else -> DocumentFormat.ZIP_UNKNOWN
            }
        }
        if (zip.getEntry(CONTENT_TYPES_ENTRY) != null) {
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.any { it.startsWith("word/") }) return DocumentFormat.DOCX
            if (names.any { it.startsWith("xl/") }) return DocumentFormat.XLSX
            if (names.any { it.startsWith("ppt/") }) return DocumentFormat.PPTX
        }
        return DocumentFormat.ZIP_UNKNOWN
    }

    /** Reads up to [limit] leading bytes; returns an empty array when the file is missing/unreadable. */
    private fun readHeader(file: File, limit: Int): ByteArray {
        if (!file.isFile) return ByteArray(0)
        return try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(limit)
                var offset = 0
                while (offset < limit) {
                    val read = input.read(buffer, offset, limit - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset == limit) buffer else buffer.copyOf(offset)
            }
        } catch (e: IOException) {
            ByteArray(0)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
