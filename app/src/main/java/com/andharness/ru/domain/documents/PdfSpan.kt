package com.andharness.ru.domain.documents


/**
 * Pure (Android- and PdfBox-free) layout engine for the PDF writers behind `pdf_create` and
 * `pdf_from_markdown`. It turns styled logical lines into wrapped, paginated physical lines; the
 * actual glyph widths are injected as a `measure` function, so the caller supplies the embedded
 * font's metrics and the engine stays unit-testable on a bare JVM (PdfBox-Android resolves font
 * metrics from Android assets, so it cannot run in a plain JVM test).
 *
 * Layout model:
 * - A logical line is a list of [PdfSpan] runs (a run is a stretch of text with one emphasis flag);
 *   the [PdfLineStyle] decides font size, leading and left indent.
 * - Wrapping is character-based, so a single "word" longer than the content width still fits on the
 *   page instead of overflowing the margin.
 * - Pagination is greedy: a physical line is pushed to the next page when it would cross the bottom
 *   margin. A page that is already empty always accepts the line, so a single line taller than the
 *   content area can never loop forever.
 *
 * References:
 * - PdfBox-Android (`PDFont.getStringWidth` supplies the metric used in production) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Apache PDFBox `PDFont#getStringWidth(String)` (thousandths of a unit of text space) —
 *   https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/font/PDFont.html
 */
data class PdfSpan(val text: String, val bold: Boolean = false)
