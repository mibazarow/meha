package com.andharness.ru.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the pragmatic Markdown subset parser used by `pdf_from_markdown`. */
class MarkdownPdfParserTest {

    private fun only(markdown: String): PdfLogicalLine = MarkdownPdfParser.parse(markdown).single()

    @Test
    fun h1AndH2HeadingsAreRecognised() {
        val h1 = only("# Заголовок")
        assertEquals(PdfLineStyle.HEADING1, h1.style)
        assertEquals("Заголовок", h1.plainText)

        val h2 = only("## Подзаголовок")
        assertEquals(PdfLineStyle.HEADING2, h2.style)
        assertEquals("Подзаголовок", h2.plainText)

        // ### and deeper degrade to H2 rather than guessing a new level.
        assertEquals(PdfLineStyle.HEADING2, only("### Глубокий").style)
    }

    @Test
    fun allThreeBulletMarkersAreRecognised() {
        for (marker in listOf("- ", "* ", "+ ")) {
            val line = only("${marker}пункт")
            assertEquals("маркер «$marker»", PdfLineStyle.BULLET, line.style)
            assertEquals("пункт", line.plainText)
        }
    }

    @Test
    fun wholeLineBoldBecomesABoldRun() {
        val line = only("**жирный**")
        assertEquals(PdfLineStyle.BODY, line.style)
        assertEquals("жирный", line.plainText)
        assertEquals(1, line.spans.size)
        assertTrue(line.spans.single().bold)
    }

    @Test
    fun inlineBoldSplitsIntoRunsAndKeepsText() {
        val line = only("до **середина** после")
        assertEquals("до середина после", line.plainText)
        assertEquals(3, line.spans.size)
        assertFalse(line.spans[0].bold)
        assertTrue(line.spans[1].bold)
        assertFalse(line.spans[2].bold)
        assertEquals("середина", line.spans[1].text)
    }

    @Test
    fun unmatchedBoldMarkerIsPassedThroughVerbatim() {
        val line = only("a **b")
        assertEquals("a **b", line.plainText)
        assertTrue(line.spans.none { it.bold })
    }

    @Test
    fun unsupportedSyntaxIsRenderedAsPlainText() {
        val samples = listOf("> цитата", "```", "| a | b |", "[ссылка](https://x)", "1. пункт", "---")
        for (sample in samples) {
            val line = only(sample)
            assertEquals("«$sample» должен остаться текстом", PdfLineStyle.BODY, line.style)
            assertEquals(sample, line.plainText)
        }
    }

    @Test
    fun paragraphsAndBlankLinesArePreserved() {
        val lines = MarkdownPdfParser.parse("Первый абзац\n\nВторой абзац")
        assertEquals(3, lines.size)
        assertEquals(PdfLineStyle.BODY, lines[0].style)
        assertTrue(lines[1].isBlank)
        assertEquals("Второй абзац", lines[2].plainText)
    }

    @Test
    fun emptyInputYieldsOneBlankLine() {
        val lines = MarkdownPdfParser.parse("")
        assertEquals(1, lines.size)
        assertTrue(lines.single().isBlank)
    }

    @Test
    fun hashtagWithoutSpaceIsNotAHeading() {
        assertEquals("#незаголовок", only("#незаголовок").plainText)
        assertEquals(PdfLineStyle.BODY, only("#незаголовок").style)
    }
}
