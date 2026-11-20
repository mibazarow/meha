package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [TextChunker] (update.md idea #4): chunk sizes stay within the target, adjacent
 * chunks overlap, boundaries land on sentences, and offsets point back into the normalised text.
 */
class TextChunkerTest {

    @Test
    fun blankTextProducesNoChunks() {
        assertEquals(emptyList<TextChunker.Chunk>(), TextChunker.chunk(""))
        assertEquals(emptyList<TextChunker.Chunk>(), TextChunker.chunk("   \n\t  "))
    }

    @Test
    fun shortTextIsOneChunkStartingAtZero() {
        val chunks = TextChunker.chunk("Краткий договор о сроках.")
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(0, chunks[0].startChar)
        assertEquals("Краткий договор о сроках.", chunks[0].text)
    }

    @Test
    fun longTextIsSplitWithoutExceedingTheChunkSize() {
        val text = buildString { repeat(40) { append("Предложение номер $it описывает срок действия договора. ") } }
        val chunks = TextChunker.chunk(text)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size > 3)
        chunks.forEach { chunk ->
            assertTrue(
                "chunk ${chunk.index} is too long: ${chunk.text.length}",
                chunk.text.length <= TextChunker.CHUNK_CHARS
            )
        }
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }

    @Test
    fun adjacentChunksOverlap() {
        val text = buildString { repeat(40) { append("Предложение номер $it описывает срок действия договора. ") } }
        val chunks = TextChunker.chunk(text)
        assertTrue(chunks.size >= 2)
        chunks.zipWithNext().forEach { (previous, next) ->
            val tail = previous.text.takeLast(40)
            assertTrue(
                "chunk ${next.index} must overlap the tail of chunk ${previous.index}",
                next.text.contains(tail)
            )
        }
    }

    @Test
    fun chunksEndOnSentenceBoundaries() {
        val text = buildString { repeat(30) { append("Строка $it содержит условие. ") } }
        TextChunker.chunk(text).forEach { chunk ->
            val last = chunk.text.last()
            assertTrue(
                "chunk ${chunk.index} does not end on a sentence boundary: «${chunk.text.takeLast(20)}»",
                last == '.' || last == '!' || last == '?' || last == '…'
            )
        }
    }

    @Test
    fun aSingleOverlongSentenceIsHardSplit() {
        val sentence = "а".repeat(2_000)
        val chunks = TextChunker.chunk(sentence)
        assertTrue("expected a hard split, got ${chunks.size}", chunks.size >= 3)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= TextChunker.CHUNK_CHARS)
            assertTrue(chunk.text.isNotBlank())
        }
    }

    @Test
    fun startCharPointsIntoTheNormalisedText() {
        val raw = "Первое предложение.\r\nВторое предложение,  с  лишними  пробелами.\r\nТретье предложение."
        val text = buildString { repeat(60) { append(raw); append(' ') } }
        val normalized = normalize(text)
        TextChunker.chunk(text).forEach { chunk ->
            assertTrue(
                "startChar ${chunk.startChar} of chunk ${chunk.index} must start its text",
                normalized.substring(chunk.startChar).startsWith(chunk.text)
            )
        }
    }

    @Test
    fun customOverlapIsHonouredAndBounded() {
        val text = buildString { repeat(20) { append("Короткое предложение $it. ") } }
        val chunks = TextChunker.chunk(text, chunkChars = 200, overlapChars = 40)
        assertTrue(chunks.size > 2)
        chunks.forEach { assertTrue(it.text.length <= 200) }
    }

    /** Mirrors [TextChunker]'s own normalisation so offsets can be verified from the outside. */
    private fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(unified.length)
        var lastWasSpace = false
        for (ch in unified) {
            if (ch == ' ' || ch == '\t') {
                if (!lastWasSpace) out.append(' ')
                lastWasSpace = true
            } else {
                out.append(ch)
                lastWasSpace = ch == '\n'
            }
        }
        return out.toString()
    }
}
