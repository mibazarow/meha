package com.andharness.ru.domain.documents

/**
 * Pure output-name derivation for the dedicated DOCX tools (`docx_replace` / `docx_append`).
 *
 * Reuses [PdfOutputNames.slug] so a `file_name` becomes one sanitised path segment and can never
 * escape the target directory; the only difference from the PDF helpers is the extension.
 *
 * Pure Kotlin: no Android or filesystem access, so it is unit-tested on the JVM.
 */
object DocumentFileNames {

    private const val DOCX_SUFFIX = ".docx"

    /**
     * Appends [extension] (including the dot, e.g. `.docx`) unless [raw] already ends with it
     * case-insensitively; a blank input stays blank.
     */
    fun withExtension(raw: String, extension: String): String {
        val name = raw.trim().trimEnd('.')
        if (name.isEmpty()) return name
        return if (name.endsWith(extension, ignoreCase = true)) name else "$name$extension"
    }

    /**
     * Slugifies a bare `file_name` and gives it a `.docx` extension. Returns the empty string when
     * [name] slugifies to nothing, so the caller can fall back instead of writing a nameless file.
     */
    fun docxFromName(name: String): String {
        val slug = PdfOutputNames.slug(name)
        return if (slug.isEmpty()) "" else withExtension(slug, DOCX_SUFFIX)
    }
}
