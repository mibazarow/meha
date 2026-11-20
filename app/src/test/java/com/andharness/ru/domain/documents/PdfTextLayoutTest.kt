package com.andharness.ru.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the PDF layout engine. Font metrics are injected as a deterministic
 * monospace measure (half the font size per character), so wrapping and pagination are exact and
 * need neither Android nor PdfBox.
 */
class PdfTextLayoutTest {

    private val options = PdfLayoutOptions()
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.5f }

    private fun widthOf(line: PdfPhysicalLine): Float =
        line.spans.sumOf { (it.text.length * line.fontSize * 0.5f).toDouble() }.toFloat()

    @Test
    fun longLineWrapsToTheContentWidth() {
        val line = PdfLogicalLine(listOf(PdfSpan("a".repeat(250))))
        val pages = PdfTextLayout.paginate(listOf(line), options, measure)
        val physical = pages.single()
        assertTrue("длинная строка должна разбиться минимум на 4 строки", physical.size >= 4)
        assertTrue(
            "каждая строка должна укладываться в ширину контента",
            physical.all { widthOf(it) <= options.contentWidth + 0.01f },
        )
        assertEquals("a".repeat(250), physical.joinToString("") { it.plainText })
    }

    @Test
    fun textSpillsOntoMultiplePages() {
        val lines = (1..200).map { PdfLogicalLine(listOf(PdfSpan("строка $it"))) }
        val pages = PdfTextLayout.paginate(lines, options, measure)
        assertEquals("200 строк по 45 на страницу", 5, pages.size)
        assertEquals(45, pages.first().size)
        assertEquals(200, pages.sumOf { it.size })
    }

    @Test
    fun marginsAreRespectedForBodyAndBullets() {
        val lines = listOf(
            PdfLogicalLine(listOf(PdfSpan("обычный абзац"))),
            PdfLogicalLine(listOf(PdfSpan("пункт списка")), PdfLineStyle.BULLET),
            PdfLogicalLine(listOf(PdfSpan("a".repeat(300)))),
        )
        val physical = PdfTextLayout.paginate(lines, options, measure).flatten()
        for (line in physical) {
            assertTrue("левый отступ не может быть отрицательным", line.xOffset >= 0f)
            assertTrue(
                "строка должна укладываться в правую границу (xOffset=${line.xOffset})",
                widthOf(line) <= options.contentWidth - line.xOffset + 0.01f,
            )
        }
    }

    @Test
    fun bulletGetsGlyphAndIndent() {
        val lines = listOf(PdfLogicalLine(listOf(PdfSpan("пункт")), PdfLineStyle.BULLET))
        val physical = PdfTextLayout.paginate(lines, options, measure).single().single()
        assertEquals(18f, physical.xOffset, 0.001f)
        assertEquals("• пункт", physical.plainText)
    }

    @Test
    fun blankLinesPreserveVerticalSpacing() {
        val lines = PdfTextLayout.plainLines("a\n\nb")
        assertEquals(3, lines.size)
        val physical = PdfTextLayout.paginate(lines, options, measure).single()
        assertEquals(3, physical.size)
        assertEquals("", physical[1].plainText)
    }

    @Test
    fun emptyTextIsAnHonestError() {
        assertNotNull(PdfTextLayout.blankError(PdfTextLayout.plainLines("")))
        assertNotNull(PdfTextLayout.blankError(PdfTextLayout.plainLines("   \n\t\n")))
        assertNull(PdfTextLayout.blankError(PdfTextLayout.plainLines("текст")))
        assertTrue(PdfTextLayout.paginate(PdfTextLayout.plainLines("  \n "), options, measure).isEmpty())
    }

    @Test
    fun headingsUseLargerMetrics() {
        val h1 = PdfTextLayout.metricsFor(PdfLineStyle.HEADING1, options)
        val body = PdfTextLayout.metricsFor(PdfLineStyle.BODY, options)
        assertTrue(h1.fontSize > body.fontSize)
        assertTrue(h1.advance > body.advance)
        assertEquals(0f, h1.xOffset, 0.001f)
    }

    @Test
    fun emptySpansStillOccupyALine() {
        val wrapped = PdfTextLayout.wrapSpans(emptyList(), 12f, options.contentWidth, measure)
        assertEquals(1, wrapped.size)
        assertEquals("", wrapped.single().joinToString("") { it.text })
    }
}
