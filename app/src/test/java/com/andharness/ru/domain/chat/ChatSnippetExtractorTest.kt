package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of [ChatSnippetExtractor]: the short-text passthrough, the window around a
 * case-insensitive (Cyrillic) match, the head excerpt when the query does not occur literally (the
 * semantic-only case), and newline flattening.
 */
class ChatSnippetExtractorTest {

    @Test
    fun shortTextIsReturnedUnchanged() {
        assertEquals("привет", ChatSnippetExtractor.extract("привет", "при вет"))
    }

    @Test
    fun emptyTextYieldsEmptySnippet() {
        assertEquals("", ChatSnippetExtractor.extract("   \n  ", "привет"))
    }

    @Test
    fun windowIsCentredOnACaseInsensitiveMatch() {
        val text = "начало ".repeat(30) + "НАЙДИ меня тут" + " хвост".repeat(30)
        val snippet = ChatSnippetExtractor.extract(text, "найди меня", maxChars = 60)

        assertTrue(snippet.contains("НАЙДИ меня", ignoreCase = true))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length <= 62) // 60 + up to two ellipses
    }

    @Test
    fun matchAtTheStartHasNoLeadingEllipsis() {
        val text = "привет " + "дальше ".repeat(50)
        val snippet = ChatSnippetExtractor.extract(text, "привет", maxChars = 40)

        assertTrue(snippet.startsWith("привет"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun semanticOnlyMatchFallsBackToHeadExcerpt() {
        val text = "Это длинное сообщение без искомой фразы, но по смыслу близкое к запросу."
        val snippet = ChatSnippetExtractor.extract(text, "совершенно другой запрос", maxChars = 20)

        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.startsWith("Это длинное сообще"))
    }

    @Test
    fun newlinesAreFlattened() {
        val snippet = ChatSnippetExtractor.extract("строка один\nстрока два\nстрока три", "два")
        assertEquals("строка один строка два строка три", snippet)
    }
}
