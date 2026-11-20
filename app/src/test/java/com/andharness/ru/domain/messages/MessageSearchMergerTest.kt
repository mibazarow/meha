package com.andharness.ru.domain.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the message-search normalization and the lexical+semantic merge:
 * query collapsing, source aliases, dedup by message id, ordering by (score, recency, id) and the
 * limit.
 */
class MessageSearchMergerTest {

    // ---------------------------------------------------------------- normalization

    @Test
    fun normalizeQueryCollapsesWhitespaceAndTrims() {
        assertEquals("привет мир", MessageSearchNormalization.normalizeQuery("  привет \n\t мир  "))
        assertEquals("", MessageSearchNormalization.normalizeQuery("   \n "))
    }

    @Test
    fun normalizeSourceMapsAliasesAndAll() {
        assertEquals(StoredMessageSource.SMS, MessageSearchNormalization.normalizeSource("sms"))
        assertEquals(StoredMessageSource.MMS, MessageSearchNormalization.normalizeSource("MMS"))
        assertEquals(
            StoredMessageSource.MESSENGER,
            MessageSearchNormalization.normalizeSource(" messenger ")
        )
        assertEquals(StoredMessageSource.EMAIL, MessageSearchNormalization.normalizeSource("email"))
        assertEquals(StoredMessageSource.CHAT, MessageSearchNormalization.normalizeSource("chat"))
        assertNull(MessageSearchNormalization.normalizeSource("all"))
        assertNull(MessageSearchNormalization.normalizeSource("*"))
        assertNull(MessageSearchNormalization.normalizeSource(null))
        // Unknown tokens pass through upper-cased so a newer source type still works.
        assertEquals("RCS", MessageSearchNormalization.normalizeSource("rcs"))
    }

    // ---------------------------------------------------------------- merge

    @Test
    fun mergeDedupsAndPutsSemanticHitsFirstByScore() {
        val merged = MessageSearchMerger.merge(
            lexicalIds = listOf(10L, 20L, 30L),
            semanticHits = listOf(30L to 0.9f, 40L to 0.8f),
            atById = mapOf(10L to 100L, 20L to 200L, 30L to 300L, 40L to 50L),
            limit = 10,
        )

        // 30 is semantic (0.9); 40 is semantic (0.8); lexical-only 10/20 keep 0.0 and fall back to
        // newest-first (20 at 200ms before 10 at 100ms).
        assertEquals(listOf(30L, 40L, 20L, 10L), merged.map { it.first })
        assertEquals(0.9, merged.first { it.first == 30L }.second, 1e-6)
        assertEquals(0.8, merged.first { it.first == 40L }.second, 1e-6)
    }

    @Test
    fun mergeOrdersLexicalOnlyHitsByRecencyThenId() {
        val merged = MessageSearchMerger.merge(
            lexicalIds = listOf(1L, 2L, 3L),
            semanticHits = emptyList(),
            atById = mapOf(1L to 100L, 2L to 300L, 3L to 300L),
            limit = 10,
        )

        // 2 and 3 share the newest timestamp; the higher id wins the deterministic tie-break.
        assertEquals(listOf(3L, 2L, 1L), merged.map { it.first })
    }

    @Test
    fun mergeKeepsBestSemanticScoreForDuplicateIds() {
        val merged = MessageSearchMerger.merge(
            lexicalIds = listOf(5L),
            semanticHits = listOf(5L to 0.4f, 5L to 0.7f),
            atById = mapOf(5L to 10L),
            limit = 10,
        )

        assertEquals(1, merged.size)
        assertEquals(0.7, merged.single().second, 1e-6)
    }

    @Test
    fun mergeDropsIdsMissingFromTheResolvedMap() {
        val merged = MessageSearchMerger.merge(
            lexicalIds = listOf(1L, 2L),
            semanticHits = listOf(9L to 0.9f),
            atById = mapOf(1L to 10L),
            limit = 10,
        )

        assertEquals(listOf(1L), merged.map { it.first })
    }

    @Test
    fun mergeBoundsTheUnionAndRejectsNonPositiveLimit() {
        val merged = MessageSearchMerger.merge(
            lexicalIds = listOf(1L, 2L, 3L),
            semanticHits = listOf(9L to 1f),
            atById = mapOf(1L to 1L, 2L to 2L, 3L to 3L, 9L to 9L),
            limit = 2,
        )
        assertEquals(listOf(9L, 3L), merged.map { it.first })
        assertTrue(
            MessageSearchMerger.merge(listOf(1L), listOf(2L to 1f), mapOf(1L to 1L, 2L to 2L), 0)
                .isEmpty()
        )
    }
}
