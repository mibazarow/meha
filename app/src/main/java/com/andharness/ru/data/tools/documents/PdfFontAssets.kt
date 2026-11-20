package com.andharness.ru.data.tools.documents

/**
 * Asset path and selection logic for the Unicode font used by the PDF writer.
 *
 * PdfBox's standard-14 base fonts (`PDType1Font.HELVETICA`, …) are WinAnsi-encoded, so Cyrillic and
 * other non-Latin text cannot be written with them: `showText` would throw, and the previous
 * sanitizer therefore replaced every such character with `?`. Embedding a TrueType font with a
 * CID/Type0 encoding keeps the real Unicode text inside the PDF.
 *
 * Only the *decision* lives here — which font to use, given a loader for the embedded asset and a
 * fallback builder. It touches neither Android nor PdfBox, so it is unit-testable on a bare JVM.
 * The caller owns both closures (asset stream + `PDType0Font.load`, standard-14 font).
 *
 * References:
 * - PdfBox-Android `PDType0Font.load(PDDocument, InputStream)` (embed subset, Type0/CIDFontType2) —
 *   https://github.com/TomRoush/PdfBox-Android
 * - Apache PDFBox `PDType0Font` — https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/font/PDType0Font.html
 * - Android assets (`AssetManager.open`) —
 *   https://developer.android.com/reference/android/content/res/AssetManager#open(java.lang.String)
 */
object PdfFontAssets {

    /**
     * Unicode TrueType font bundled in `assets/`: DejaVu Sans, which covers Latin, Cyrillic and
     * Greek. License (Bitstream Vera derived) ships next to it as `DejaVuSans.LICENSE.txt`.
     */
    const val UNICODE_FONT_ASSET = "fonts/DejaVuSans.ttf"

    /**
     * Builds the writing font: the value from [loadUnicode] when it succeeds and is non-null,
     * otherwise the value from [fallback]. A missing or unreadable asset therefore degrades to the
     * previous standard-14 behaviour instead of failing the whole document write.
     */
    fun <F> select(loadUnicode: () -> F?, fallback: () -> F): F =
        runCatching { loadUnicode() }.getOrNull() ?: fallback()
}
