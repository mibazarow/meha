package com.andharness.ru.domain.documents


/** One author-level line: styled runs plus the block style they are laid out with. */
data class PdfLogicalLine(
    val spans: List<PdfSpan>,
    val style: PdfLineStyle = PdfLineStyle.BODY,
) {
    val plainText: String get() = spans.joinToString("") { it.text }

    val isBlank: Boolean get() = spans.all { it.text.isBlank() }
}
