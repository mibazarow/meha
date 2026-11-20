package com.andharness.ru.domain.documents

/**
 * Pragmatic Markdown subset parser for `pdf_from_markdown`. It deliberately supports only the
 * constructs the PDF writer can render faithfully and leaves everything else as plain text:
 *
 * Supported:
 * - headings: `# ` → [PdfLineStyle.HEADING1]; `## ` (and `###`+) → [PdfLineStyle.HEADING2]
 * - bullet lists: a trimmed line starting with `- `, `* ` or `+ ` → [PdfLineStyle.BULLET]
 *   (the bullet glyph is added by the layout engine, not stored in the text)
 * - inline bold: `**text**` → a [PdfSpan] with `bold = true`; an odd number of `**` markers is
 *   treated as literal text so nothing is silently dropped
 * - paragraphs: every other non-empty line → [PdfLineStyle.BODY]
 * - blank lines are preserved as empty logical lines (paragraph spacing)
 *
 * Not supported (rendered verbatim as plain text, documented honestly in the tool description):
 * tables, fenced/indented code, block quotes (`>`), links/images (`[x](y)`), numbered lists,
 * horizontal rules, nested lists and any other Markdown syntax.
 *
 * Pure Kotlin: no Android or PdfBox imports, so it is unit-tested on the JVM.
 */
object MarkdownPdfParser {

    /** Parses [markdown] into styled logical lines ready for [PdfTextLayout.paginate]. */
    fun parse(markdown: String): List<PdfLogicalLine> =
        markdown.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { line -> parseLine(line) }

    private fun parseLine(raw: String): PdfLogicalLine {
        val trimmed = raw.trim()
        val bullet = bulletText(trimmed)
        return when {
            trimmed.isEmpty() -> PdfLogicalLine(listOf(PdfSpan("")))
            trimmed.startsWith("# ") ->
                PdfLogicalLine(parseInline(trimmed.removePrefix("# ").trim()), PdfLineStyle.HEADING1)
            trimmed.startsWith("## ") ->
                PdfLogicalLine(parseInline(trimmed.removePrefix("## ").trim()), PdfLineStyle.HEADING2)
            trimmed.startsWith("###") && trimmed.length > 3 && trimmed[3] == ' ' ->
                PdfLogicalLine(
                    parseInline(trimmed.removePrefix("###").trim()),
                    PdfLineStyle.HEADING2,
                )
            bullet != null -> PdfLogicalLine(parseInline(bullet), PdfLineStyle.BULLET)
            else -> PdfLogicalLine(parseInline(trimmed), PdfLineStyle.BODY)
        }
    }

    /** The bullet item text for a `-`/`*`/`+` list line, or `null` when the line is not a bullet. */
    private fun bulletText(trimmed: String): String? {
        for (marker in BULLET_MARKERS) {
            if (trimmed.startsWith(marker)) {
                return trimmed.removePrefix(marker).trim()
            }
        }
        return null
    }

    /**
     * Splits [text] into runs on `**...**` pairs. With an uneven number of markers the whole line is
     * returned as a single plain run — unsupported syntax is passed through, never invented away.
     */
    fun parseInline(text: String): List<PdfSpan> {
        if (text.isEmpty()) return listOf(PdfSpan(""))
        if (markerCount(text) % 2 != 0) return listOf(PdfSpan(text))
        val spans = mutableListOf<PdfSpan>()
        val buffer = StringBuilder()
        var bold = false
        var index = 0
        while (index < text.length) {
            if (text.startsWith(BOLD_MARKER, index)) {
                if (buffer.isNotEmpty()) {
                    spans += PdfSpan(buffer.toString(), bold)
                    buffer.setLength(0)
                }
                bold = !bold
                index += BOLD_MARKER.length
            } else {
                buffer.append(text[index])
                index++
            }
        }
        if (buffer.isNotEmpty()) spans += PdfSpan(buffer.toString(), bold)
        if (spans.isEmpty()) spans += PdfSpan("")
        return spans
    }

    private fun markerCount(text: String): Int {
        var count = 0
        var index = text.indexOf(BOLD_MARKER)
        while (index >= 0) {
            count++
            index = text.indexOf(BOLD_MARKER, index + BOLD_MARKER.length)
        }
        return count
    }

    private const val BOLD_MARKER = "**"
    private val BULLET_MARKERS = listOf("- ", "* ", "+ ")
}
