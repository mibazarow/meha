package com.andharness.ru.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DocumentChunker]: the splitter must produce bounded, non-blank, embedder-safe
 * chunks with overlap, and never overflow its character budget.
 */
class DocumentChunkerTest {

    @Test
    fun blankTextYieldsNoChunks() {
        assertTrue(DocumentChunker.chunk("").isEmpty())
        assertTrue(DocumentChunker.chunk("   \n\t ").isEmpty())
    }

    @Test
    fun shortTextIsASingleChunk() {
        val text = "Короткий договор действует тридцать дней."
        val chunks = DocumentChunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun estimateTokensUsesTheCharacterHeuristic() {
        assertEquals(0, DocumentChunker.estimateTokens(""))
        assertEquals(1, DocumentChunker.estimateTokens("a"))
        assertEquals(1, DocumentChunker.estimateTokens("abcd"))
        assertEquals(2, DocumentChunker.estimateTokens("abcde"))
    }

    @Test
    fun longTextIsSplitIntoBoundedChunks() {
        val text = (1..600).joinToString(" ") { "слово$it" }
        val chunks = DocumentChunker.chunk(text)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size >= 2)
        assertTrue(chunks.all { it.isNotBlank() })
        assertTrue(chunks.all { it.length <= DocumentChunker.MAX_CHARS })
    }

    @Test
    fun chunksOverlapSoNoSentenceIsLost() {
        val text = (1..600).joinToString(" ") { "токен$it" }
        val chunks = DocumentChunker.chunk(text)
        // Overlap duplicates characters across boundaries, so the total is longer than the input.
        assertTrue(chunks.sumOf { it.length } > text.length)
    }

    @Test
    fun aSingleVeryLongWordIsHardSplitWithinBudget() {
        val chunks = DocumentChunker.chunk("a".repeat(5_000))
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.length <= DocumentChunker.MAX_CHARS })
    }

    /**
     * Regression guard for the "input … is too large to process" bug: a chunk must stay under the
     * embedder's per-request token ceiling even when the text tokenises as densely as Cyrillic
     * (≈2 chars/token, vs the 4-chars/token sizing heuristic). The EmbeddingGemma 2 server runs with
     * its physical batch raised to its 2048-token context, and we keep a 2x safety margin below it.
     */
    @Test
    fun worstCaseChunkStaysUnderTheEmbedderPerRequestCeiling() {
        val embedderPerRequestCeilingTokens = 2048
        val cyrillicCharsPerToken = 2
        val worstCaseTokens = DocumentChunker.MAX_CHARS / cyrillicCharsPerToken
        assertTrue(
            "worst-case chunk ($worstCaseTokens tokens) must leave margin under $embedderPerRequestCeilingTokens",
            worstCaseTokens <= embedderPerRequestCeilingTokens / 2,
        )
    }

    @Test
    fun customBudgetIsRespected() {
        val text = (1..50).joinToString(" ") { "w$it" }
        val chunks = DocumentChunker.chunk(text, maxChars = 20, overlapChars = 5)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.length <= 20 })
    }
}
