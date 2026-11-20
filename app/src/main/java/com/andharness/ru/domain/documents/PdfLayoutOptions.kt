package com.andharness.ru.domain.documents


/**
 * Page geometry and per-style typography. Defaults are A4 (595.28 x 841.89 pt, the same rectangle
 * as PdfBox `PDRectangle.A4`) with a 56 pt (≈2 cm) margin and a 12 pt body.
 */
data class PdfLayoutOptions(
    val pageWidth: Float = 595.28f,
    val pageHeight: Float = 841.89f,
    val margin: Float = 56f,
    val bodyFontSize: Float = 12f,
    val bodyLeading: Float = 16f,
    val heading1FontSize: Float = 20f,
    val heading1Leading: Float = 26f,
    val heading2FontSize: Float = 15f,
    val heading2Leading: Float = 21f,
    val bulletIndent: Float = 18f,
) {
    val contentWidth: Float get() = pageWidth - 2f * margin

    val contentHeight: Float get() = pageHeight - 2f * margin
}
