package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Построение FTS4 `MATCH`-выражения. Выражение должно покрывать формы слов
 * (префиксы), синонимы и не ломаться на служебных словах FTS.
 */
class MemoryFtsQueryTest {

    @Test
    fun emptyOrStopwordOnlyQueryProducesNoTerms() {
        assertEquals("", MemoryFtsQuery.build(""))
        assertEquals("", MemoryFtsQuery.build("   "))
        assertEquals("", MemoryFtsQuery.build("и где как"))
    }

    @Test
    fun inflectionIsCoveredByPrefixTerms() {
        val match = MemoryFtsQuery.build("как я решил вопрос с налоговой")

        assertTrue("must keep a prefix term for the inflected word", match.contains("\"нало\"*"))
        assertTrue("must keep the raw 4-char prefix", match.contains("\"вопр\"*"))
        assertTrue("terms are OR-combined", match.contains(" OR "))
    }

    @Test
    fun synonymsAreExpandedForTheMatch() {
        val match = MemoryFtsQuery.build("налоговая")

        assertTrue("synonym expansion must reach ФНС", match.contains("\"фнс\""))
    }

    @Test
    fun englishSynonymsAreExpandedToo() {
        assertTrue(MemoryFtsQuery.build("tax").contains("\"фнс\""))
    }

    @Test
    fun caseIsNormalised() {
        val upper = MemoryFtsQuery.build("НАЛОГОВАЯ")
        val lower = MemoryFtsQuery.build("налоговая")
        assertEquals(lower, upper)
    }

    @Test
    fun ftsOperatorsAreQuotedSoTheyCannotBreakTheExpression() {
        val match = MemoryFtsQuery.build("not near")

        assertTrue(match.contains("\"not\""))
        assertFalse("a bare operator would change the query meaning", match.startsWith("not "))
    }

    @Test
    fun everyTermIsQuoted() {
        MemoryFtsQuery.build("налоговая встреча").split(" OR ").forEach { term ->
            assertTrue("term must be quoted: $term", term.startsWith("\""))
        }
    }
}
