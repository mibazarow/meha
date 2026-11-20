package com.andharness.ru.data.tools.documents

import android.content.Context
import com.andharness.ru.domain.documents.PdfLayoutOptions
import com.andharness.ru.domain.documents.PdfLogicalLine
import com.andharness.ru.domain.documents.PdfTextLayout
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * PdfBox-backed PDF writing shared by `doc_create` (PDF), `pdf_create`/`pdf_from_markdown` and the
 * PDF branch of `doc_edit`: layout, embedded-font selection, sanitising and post-write verification.
 */
internal class PdfWriter(
    private val context: Context,
) {

    /** Produces the file bytes for [format]; unsupported formats fail with an honest message. */
    fun buildDocument(format: DocumentFormat, content: String, title: String?): ByteArray =
        when (format) {
            DocumentFormat.PDF -> createPdf(content, title)
            DocumentFormat.DOCX -> DocumentWriter.docx(content, title)
            DocumentFormat.XLSX -> DocumentWriter.xlsx(DocumentWriter.parseTableText(content))
            DocumentFormat.ODT -> DocumentWriter.odt(content, title)
            DocumentFormat.ODS -> DocumentWriter.ods(DocumentWriter.parseTableText(content))
            DocumentFormat.RTF -> DocumentWriter.rtf(content)
            DocumentFormat.CSV -> DocumentWriter.csv(DocumentWriter.parseTableText(content), ',')
            DocumentFormat.TSV -> DocumentWriter.csv(DocumentWriter.parseTableText(content), '\t')
            DocumentFormat.JSON -> {
                try {
                    Json.parseToJsonElement(content)
                } catch (e: Exception) {
                    throw IllegalArgumentException("содержимое не является корректным JSON (${e.message})")
                }
                content.toByteArray(Charsets.UTF_8)
            }
            DocumentFormat.XML, DocumentFormat.HTML, DocumentFormat.MARKDOWN, DocumentFormat.TEXT ->
                content.toByteArray(Charsets.UTF_8)
            else -> throw IllegalArgumentException("формат ${format.name} не поддерживается для создания.")
        }

    /** Renders [text] as an A4 PDF: wrapped to the page width, paginated, one line per row. */
    fun createPdf(text: String, title: String?): ByteArray {
        val fontSize = 12f
        val leading = 16f
        val margin = 56f
        val maxWidth = PDRectangle.A4.width - 2 * margin
        val linesPerPage = ((PDRectangle.A4.height - 2 * margin) / leading).toInt().coerceAtLeast(1)

        val document = PDDocument()
        try {
            val font = pdfFont(document)
            val lines = mutableListOf<String>()
            for (raw in text.split('\n')) {
                lines += wrapPdfLine(sanitizePdfText(raw.removeSuffix("\r"), font), font, fontSize, maxWidth)
            }

            if (title != null) {
                val info = document.documentInformation
                info.title = sanitizePdfText(title, font)
            }
            for (pageLines in lines.chunked(linesPerPage)) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, fontSize)
                    stream.newLineAtOffset(margin, PDRectangle.A4.height - margin)
                    stream.setLeading(leading)
                    var first = true
                    for (line in pageLines) {
                        if (!first) stream.newLineAtOffset(0f, -leading)
                        stream.showText(line)
                        first = false
                    }
                    stream.endText()
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    /**
     * Lays [lines] out with [PdfTextLayout] against the real A4 page and draws the pages with the
     * embedded Unicode font, returning the PDF bytes plus the page count. Character widths come from
     * the loaded font (`PDFont.getStringWidth`), which keeps wrapping exact for the font actually
     * used. Bold runs are emulated with `RenderingMode.FILL_STROKE` (the bundled asset has no bold
     * face), and the rendering mode is reset to `FILL` before each text object ends.
     *
     * References:
     * - PdfBox-Android `PDPageContentStream` (`beginText`/`setFont`/`newLineAtOffset`/`showText`/
     *   `setRenderingMode`/`setLineWidth`) — https://github.com/TomRoush/PdfBox-Android
     * - Apache PDFBox `RenderingMode` — https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/graphics/state/RenderingMode.html
     */
    fun buildPdfFromLines(
        lines: List<PdfLogicalLine>,
        title: String?,
    ): Pair<ByteArray, Int> {
        val options = PdfLayoutOptions(
            pageWidth = PDRectangle.A4.width,
            pageHeight = PDRectangle.A4.height,
        )
        val document = PDDocument()
        try {
            val font = pdfFont(document)
            val measure = { text: String, size: Float -> pdfTextWidth(text, font, size) }
            val pages = PdfTextLayout.paginate(lines, options, measure)
            if (pages.isEmpty()) throw IllegalArgumentException(PdfTextLayout.BLANK_ERROR)
            if (title != null) {
                document.documentInformation.title = sanitizePdfText(title, font)
            }
            for (pageLines in pages) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    // Baseline arithmetic: the first baseline sits one font size below the top
                    // margin, then each following baseline advances by the previous line's leading.
                    var y = options.pageHeight - options.margin
                    for (line in pageLines) {
                        y -= line.fontSize
                        stream.beginText()
                        stream.setFont(font, line.fontSize)
                        stream.newLineAtOffset(options.margin + line.xOffset, y)
                        var stroking = false
                        for (span in line.spans) {
                            if (span.bold != stroking) {
                                if (span.bold) {
                                    stream.setRenderingMode(RenderingMode.FILL_STROKE)
                                    stream.setLineWidth(line.fontSize * BOLD_STROKE_RATIO)
                                } else {
                                    stream.setRenderingMode(RenderingMode.FILL)
                                }
                                stroking = span.bold
                            }
                            if (span.text.isNotEmpty()) {
                                stream.showText(sanitizePdfText(span.text, font))
                            }
                        }
                        if (stroking) stream.setRenderingMode(RenderingMode.FILL)
                        stream.endText()
                        y -= line.advance - line.fontSize
                    }
                }
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray() to pages.size
        } finally {
            document.close()
        }
    }

    /**
     * Re-opens the freshly written file: it must exist, be non-empty, start with the `%PDF-`
     * signature and have exactly [expectedPages] pages. Returns an honest error message, or `null`.
     */
    fun verifyPdfFile(file: File, expectedPages: Int): String? {
        if (!file.isFile) return "созданный файл недоступен."
        if (file.length() == 0L) return "созданный файл пуст."
        val header = ByteArray(PDF_HEADER.length)
        val read = FileInputStream(file).use { it.read(header) }
        if (read < PDF_HEADER.length ||
            String(header, 0, read, Charsets.US_ASCII) != PDF_HEADER
        ) {
            return "файл не начинается с сигнатуры $PDF_HEADER."
        }
        val pages = try {
            PDDocument.load(file).use { it.numberOfPages }
        } catch (e: Exception) {
            return "не удалось повторно открыть PDF: ${e.message}"
        }
        if (pages != expectedPages) return "страниц $pages, ожидалось $expectedPages."
        return null
    }

    /**
     * Resolves the font used to write [document]: the embedded Unicode TrueType asset
     * (`PDType0Font.load`, which keeps Cyrillic/non-Latin text readable) when it can be opened,
     * otherwise the previous standard-14 WinAnsi font. The asset may legitimately be absent in a
     * stripped build, so a failure degrades instead of aborting the write — see [PdfFontAssets].
     *
     * References:
     * - PdfBox-Android `PDType0Font.load(PDDocument, InputStream)` —
     *   https://github.com/TomRoush/PdfBox-Android
     */
    fun pdfFont(document: PDDocument): PDFont =
        PdfFontAssets.select(
            loadUnicode = {
                context.assets.open(PdfFontAssets.UNICODE_FONT_ASSET).use { input ->
                    PDType0Font.load(document, input)
                }
            },
            fallback = { PDType1Font.HELVETICA },
        )

    /**
     * Wraps one logical line to [maxWidth] points at the current [font]/[fontSize]. Breaking is
     * character-based, so a single word longer than the page still fits instead of overflowing.
     */
    fun wrapPdfLine(
        line: String,
        font: PDFont,
        fontSize: Float,
        maxWidth: Float,
    ): List<String> {
        if (line.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var width = 0f
        for (ch in line) {
            val charWidth = pdfTextWidth(ch.toString(), font, fontSize)
            if (current.isNotEmpty() && width + charWidth > maxWidth) {
                result += current.toString()
                current.setLength(0)
                width = 0f
            }
            current.append(ch)
            width += charWidth
        }
        if (current.isNotEmpty() || result.isEmpty()) result += current.toString()
        return result
    }

    fun pdfTextWidth(text: String, font: PDFont, fontSize: Float): Float =
        try {
            font.getStringWidth(text) / 1000f * fontSize
        } catch (e: Exception) {
            text.length * fontSize * 0.5f
        }

    /**
     * Replaces characters the current [font] cannot encode with `?`, so `showText` never throws on
     * model output. With the embedded Unicode font (see [pdfFont]) this is a no-op for Cyrillic and
     * other non-Latin text; it only kicks in for the standard-14 WinAnsi fallback or for code points
     * DejaVu Sans genuinely lacks.
     */
    fun sanitizePdfText(text: String, font: PDFont): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            val encodable = try {
                font.encode(ch.toString())
                true
            } catch (e: Exception) {
                false
            }
            builder.append(if (encodable) ch else '?')
        }
        return builder.toString()
    }

    private companion object {
        /** Mandatory first bytes of every PDF, checked after the write. */
        const val PDF_HEADER = "%PDF-"

        /** Stroke width as a fraction of the font size used to emulate bold (no bold asset). */
        const val BOLD_STROKE_RATIO = 0.03f
    }
}
