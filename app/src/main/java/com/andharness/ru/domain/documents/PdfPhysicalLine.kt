package com.andharness.ru.domain.documents


/** One rendered text line: the runs to draw, their font size, advance and left indent. */
data class PdfPhysicalLine(
    val spans: List<PdfSpan>,
    val fontSize: Float,
    val advance: Float,
    val xOffset: Float,
    val style: PdfLineStyle,
) {
    val plainText: String get() = spans.joinToString("") { it.text }
}
