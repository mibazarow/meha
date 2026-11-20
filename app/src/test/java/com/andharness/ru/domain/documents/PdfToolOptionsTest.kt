package com.andharness.ru.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [PdfToolOptions]: the argument grammar shared by the newer PDF tools
 * (`pdf_rotate`, `pdf_watermark`, `pdf_render_pages`). No Android/PdfBox runtime is touched here —
 * the parsing and page-name math are the parts that must not silently misbehave.
 */
class PdfToolOptionsTest {

    @Test
    fun rotationAcceptsMultiplesOf90AndNormalises() {
        assertEquals(90, PdfToolOptions.rotationDegrees("90"))
        assertEquals(180, PdfToolOptions.rotationDegrees("180"))
        assertEquals(270, PdfToolOptions.rotationDegrees("270"))
        assertEquals(0, PdfToolOptions.rotationDegrees("0"))
        assertEquals(270, PdfToolOptions.rotationDegrees("-90"))
        assertEquals(0, PdfToolOptions.rotationDegrees("360"))
        assertEquals(90, PdfToolOptions.rotationDegrees("450"))
        assertEquals(90, PdfToolOptions.rotationDegrees(" 90 "))
    }

    @Test
    fun rotationRejectsNonMultiplesAndGarbage() {
        assertNull(PdfToolOptions.rotationDegrees(null))
        assertNull(PdfToolOptions.rotationDegrees(""))
        assertNull(PdfToolOptions.rotationDegrees("45"))
        assertNull(PdfToolOptions.rotationDegrees("abc"))
    }

    @Test
    fun opacityAcceptsUnitRangeOnly() {
        assertEquals(0f, PdfToolOptions.opacity("0")!!, 0.0001f)
        assertEquals(0.3f, PdfToolOptions.opacity("0.3")!!, 0.0001f)
        assertEquals(1f, PdfToolOptions.opacity("1")!!, 0.0001f)
        assertNull(PdfToolOptions.opacity(null))
        assertNull(PdfToolOptions.opacity(""))
        assertNull(PdfToolOptions.opacity("-0.1"))
        assertNull(PdfToolOptions.opacity("1.1"))
        assertNull(PdfToolOptions.opacity("half"))
    }

    @Test
    fun colorParsesHexRgbAndChannelTriples() {
        assertEquals(Triple(255, 255, 255), PdfToolOptions.color("#fff"))
        assertEquals(Triple(255, 0, 0), PdfToolOptions.color("#ff0000"))
        assertEquals(Triple(0, 128, 255), PdfToolOptions.color("0,128,255"))
        assertEquals(Triple(10, 20, 30), PdfToolOptions.color(" 10 , 20 , 30 "))
        assertNull(PdfToolOptions.color(null))
        assertNull(PdfToolOptions.color(""))
        assertNull(PdfToolOptions.color("#12"))
        assertNull(PdfToolOptions.color("#ggg"))
        assertNull(PdfToolOptions.color("300,0,0"))
        assertNull(PdfToolOptions.color("1,2"))
        assertNull(PdfToolOptions.color("red"))
    }

    @Test
    fun renderDpiDefaultsAndClamps() {
        assertEquals(PdfToolOptions.DEFAULT_RENDER_DPI, PdfToolOptions.renderDpi(null))
        assertEquals(PdfToolOptions.DEFAULT_RENDER_DPI, PdfToolOptions.renderDpi("abc"))
        assertEquals(300, PdfToolOptions.renderDpi("300"))
        assertEquals(PdfToolOptions.MIN_RENDER_DPI, PdfToolOptions.renderDpi("1"))
        assertEquals(PdfToolOptions.MAX_RENDER_DPI, PdfToolOptions.renderDpi("9999"))
    }

    @Test
    fun renderScaleAndPixelsFollowThe72DpiBase() {
        assertEquals(1f, PdfToolOptions.renderScale(72), 0.0001f)
        assertEquals(150f / 72f, PdfToolOptions.renderScale(150), 0.0001f)
        assertEquals(150, PdfToolOptions.pointsToPixels(72, 150))
        assertEquals(300, PdfToolOptions.pointsToPixels(144, 150))
        // Never returns 0 even at the lowest allowed DPI for a tiny page.
        assertEquals(1, PdfToolOptions.pointsToPixels(1, 18))
    }

    @Test
    fun pageAndImageNamesAreStableAndSanitised() {
        assertEquals("page-001.png", PdfToolOptions.pageFileName("page", 1))
        assertEquals("page-012.png", PdfToolOptions.pageFileName("page", 12))
        assertEquals("page-001.png", PdfToolOptions.pageFileName("", 1))
        assertEquals("my-shot-001.png", PdfToolOptions.pageFileName("my shot!", 1))
        assertEquals("page-001-image-02.png", PdfToolOptions.imageFileName(1, 2))
        assertEquals("page-123-image-07.png", PdfToolOptions.imageFileName(123, 7))
    }

    @Test
    fun safeSegmentDropsUnsafeCharacters() {
        assertEquals("report", PdfToolOptions.safeSegment("report"))
        assertEquals("a-b-c", PdfToolOptions.safeSegment("a b/c"))
        assertEquals("", PdfToolOptions.safeSegment("Документ"))
    }
}
