package com.andharness.ru.domain.communications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage of the FTS4 `MATCH` sanitiser: user words are quoted literals joined by `OR`,
 * FTS operators in the input cannot leak, and a query without usable words yields the empty string.
 */
class TranscriptFtsQueryTest {

    @Test
    fun singleWordBecomesQuotedTerm() {
        assertEquals("\"привет\"", TranscriptFtsQuery.build("привет"))
    }

    @Test
    fun multipleWordsJoinWithOrInInputOrderWithoutDuplicates() {
        assertEquals("\"hello\" OR \"world\"", TranscriptFtsQuery.build("hello world"))
        assertEquals("\"hello\" OR \"world\"", TranscriptFtsQuery.build("hello  world hello"))
    }

    @Test
    fun ftsOperatorsAreNeutralisedByQuoting() {
        assertEquals("\"NOT\" OR \"NEAR\" OR \"OR\"", TranscriptFtsQuery.build("NOT NEAR OR"))
    }

    @Test
    fun punctuationAndSymbolsAreStripped() {
        assertEquals("\"cafe\" OR \"2024\"", TranscriptFtsQuery.build("cafe, (2024)* "))
    }

    @Test
    fun wordsAreKeptAsLiteralsSoPrefixStarIsNotAnOperator() {
        assertEquals("\"star\"", TranscriptFtsQuery.build("star*"))
    }

    @Test
    fun blankOrSymbolOnlyQueryYieldsEmptyString() {
        assertEquals("", TranscriptFtsQuery.build(""))
        assertEquals("", TranscriptFtsQuery.build("   "))
        assertEquals("", TranscriptFtsQuery.build("!@# \$%^"))
    }
}
