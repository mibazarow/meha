package com.andharness.ru.domain.documents

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure, Android-free parsing/normalisation for the newer PDF tools (`pdf_rotate`, `pdf_watermark`,
 * `pdf_render_pages`, `pdf_fill_form`). Keeping this out of the handlers means the argument grammar
 * (rotation angle, opacity, colour, DPI, output names) is unit-testable on a bare JVM, while the
 * handlers stay thin wrappers around PdfBox/`PdfRenderer`.
 *
 * References:
 * - PDFBox `PDPage.setRotation` (clockwise `/Rotate`, 0/90/180/270) —
 *   https://pdfbox.apache.org/docs/2.0.27/javadocs/org/apache/pdfbox/pdmodel/PDPage.html
 * - PdfBox-Android (`PDDocument`/`PDPage`/`PDPageContentStream`) — https://github.com/TomRoush/PdfBox-Android
 * - `android.graphics.pdf.PdfRenderer` (points = 1/72", so px = pt × dpi / 72) —
 *   https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
 */
object PdfToolOptions {

    /** Default render resolution when the caller does not pass `dpi`. */
    const val DEFAULT_RENDER_DPI = 150

    /** Lower/upper clamp for the requested render `dpi`, so a stray value cannot OOM the device. */
    const val MIN_RENDER_DPI = 18
    const val MAX_RENDER_DPI = 600

    /** Default watermark opacity (0..1) when the caller does not pass `opacity`. */
    const val DEFAULT_WATERMARK_OPACITY = 0.3f

    /** Default watermark font size in points. */
    const val DEFAULT_WATERMARK_FONT_SIZE = 48f

    /** Default watermark rotation in degrees (counter-clockwise on the page). */
    const val DEFAULT_WATERMARK_ANGLE = 45

    /**
     * Normalises a user rotation to one of `0/90/180/270` (clockwise, matching PDF `/Rotate`).
     * Returns `null` for a non-numeric value or one that is not a multiple of 90, so the caller can
     * report an honest error instead of silently rotating by a meaningless amount.
     */
    fun rotationDegrees(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        if (value % 90 != 0) return null
        return ((value % 360) + 360) % 360
    }

    /**
     * Parses a watermark opacity in `0.0..1.0`; `null` when it is unparsable or out of range. The
     * value is returned unchanged (not clamped) so a typo like `1.5` is reported, not hidden.
     */
    fun opacity(raw: String?): Float? {
        val value = raw?.trim()?.toFloatOrNull() ?: return null
        if (value.isNaN() || value < 0f || value > 1f) return null
        return value
    }

    /**
     * Parses a watermark colour into `(r, g, b)` bytes. Accepts `#rgb`, `#rrggbb` and `r,g,b`
     * (each 0..255). Returns `null` for anything else so the handler can report the bad value.
     */
    fun color(raw: String?): Triple<Int, Int, Int>? {
        val spec = raw?.trim().orEmpty()
        if (spec.isEmpty()) return null
        if (spec.startsWith("#")) {
            val hex = spec.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hexDigit(hex[0]) ?: return null
                    val g = hexDigit(hex[1]) ?: return null
                    val b = hexDigit(hex[2]) ?: return null
                    Triple(r * 17, g * 17, b * 17)
                }
                6 -> {
                    val r = hexByte(hex, 0) ?: return null
                    val g = hexByte(hex, 2) ?: return null
                    val b = hexByte(hex, 4) ?: return null
                    Triple(r, g, b)
                }
                else -> null
            }
        }
        val parts = spec.split(',').map { it.trim() }
        if (parts.size != 3) return null
        val channels = parts.map { it.toIntOrNull() ?: return null }
        if (channels.any { it !in 0..255 }) return null
        return Triple(channels[0], channels[1], channels[2])
    }

    /** Resolves the render DPI, defaulting to [DEFAULT_RENDER_DPI] and clamping into the safe range. */
    fun renderDpi(raw: String?): Int {
        val parsed = raw?.trim()?.toIntOrNull() ?: return DEFAULT_RENDER_DPI
        return parsed.coerceIn(MIN_RENDER_DPI, MAX_RENDER_DPI)
    }

    /** Pixel scale for [dpi]: PDF points are 1/72", so pixels = points × dpi / 72. */
    fun renderScale(dpi: Int): Float = dpi / 72f

    /** Converts a point dimension to pixels at [dpi], never returning 0. */
    fun pointsToPixels(points: Int, dpi: Int): Int =
        (points * renderScale(dpi)).roundToInt().coerceAtLeast(1)

    /** `page-001.png`-style name for a rendered page; [prefix] is sanitised to one path segment. */
    fun pageFileName(prefix: String, pageNumber: Int): String {
        val safe = safeSegment(prefix).ifEmpty { "page" }
        return String.format(Locale.ROOT, "%s-%03d.png", safe, pageNumber)
    }

    /** `page-001-image-02.png`-style name for an image extracted from a page. */
    fun imageFileName(pageNumber: Int, imageIndex: Int): String =
        String.format(Locale.ROOT, "page-%03d-image-%02d.png", pageNumber, imageIndex)

    /** Keeps only characters that are safe inside a single file-name segment. */
    fun safeSegment(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.')

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    private fun hexByte(hex: String, offset: Int): Int? {
        val hi = hexDigit(hex[offset]) ?: return null
        val lo = hexDigit(hex[offset + 1]) ?: return null
        return hi * 16 + lo
    }
}
