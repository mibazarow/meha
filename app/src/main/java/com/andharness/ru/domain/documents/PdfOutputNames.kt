package com.andharness.ru.domain.documents

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure file-name derivation for the PDF writers (`pdf_create` / `pdf_from_markdown`).
 *
 * The tools accept an optional `file_name` (and default to a name derived from `title` or the first
 * content line). Because the name ends up as a single path segment inside the sandbox, every
 * non-slug character is replaced with `-`; markdown markers and characters that are unsafe on a
 * filesystem are dropped rather than escaped, so the result can never escape the target directory.
 *
 * Pure Kotlin: no Android or PdfBox imports, so it is unit-tested on the JVM.
 */
object PdfOutputNames {

    /** Upper bound on the derived slug, so a long heading cannot produce an unwieldy file name. */
    const val MAX_SLUG_LENGTH = 48

    /** Fixed pattern for the timestamp fallback: sortable, filesystem-safe, locale-independent. */
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    private val MARKUP = Regex("""[#*_`>~\[\]()!]""")
    private val ILLEGAL =
        Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")
    private val WHITESPACE = Regex("""\s+""")
    private val REPEATED_DASHES = Regex("""-{2,}""")

    /**
     * Derives a `.pdf` file name: the [title] when present, otherwise the [firstLine]; when neither
     * yields a slug, falls back to `document-<yyyyMMdd-HHmmss>` built from [nowMillis].
     */
    fun defaultFileName(title: String?, firstLine: String?, nowMillis: Long): String {
        val source = title?.takeIf { it.isNotBlank() } ?: firstLine.orEmpty()
        val base = slug(source).ifEmpty { "document-${timestamp(nowMillis)}" }
        return withPdfExtension(base)
    }

    /** Slugifies arbitrary text into one safe path segment (letters, digits, `-`, `.`). */
    fun slug(raw: String): String =
        raw.replace(MARKUP, " ")
            .replace(ILLEGAL, " ")
            .replace(WHITESPACE, "-")
            .replace(REPEATED_DASHES, "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)
            .trim('-')
            .trimEnd('.')

    /** Appends `.pdf` unless [raw] already ends with it (case-insensitive); a blank input stays blank. */
    fun withPdfExtension(raw: String): String {
        val name = raw.trim().trimEnd('.')
        if (name.isEmpty()) return name
        return if (name.endsWith(PDF_SUFFIX, ignoreCase = true)) name else "$name$PDF_SUFFIX"
    }

    private fun timestamp(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).format(TIMESTAMP)

    private const val PDF_SUFFIX = ".pdf"
}
