package com.andharness.ru.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [InteractionSearchTerms]: the raw query is always kept, and the normalized
 * phone/email forms are added so a differently-shaped handle still substring-matches the identities
 * that are stored normalized (update.md §4.3, "правило нормализации").
 */
class InteractionSearchTermsTest {

    @Test
    fun blankQueriesExpandToNothing() {
        assertTrue(InteractionSearchTerms.expand(null).isEmpty())
        assertTrue(InteractionSearchTerms.expand("").isEmpty())
        assertTrue(InteractionSearchTerms.expand("   ").isEmpty())
    }

    @Test
    fun plainTextKeepsOnlyTheRawQuery() {
        assertEquals(listOf("Иван"), InteractionSearchTerms.expand("  Иван "))
    }

    @Test
    fun alreadyNormalizedPhoneKeepsRawPlusNormalizedAndMatchKey() {
        assertEquals(
            listOf("+79493401205", "9493401205"),
            InteractionSearchTerms.expand("+79493401205")
        )
    }

    @Test
    fun localPhoneShapeAlsoMatchesTheNormalizedIdentity() {
        // 8 949 ... must still find an identity stored as +79493401205.
        assertTrue(InteractionSearchTerms.expand("8 949 340-12-05").contains("+79493401205"))
        assertTrue(InteractionSearchTerms.expand("89493401205").contains("+79493401205"))
        assertTrue(InteractionSearchTerms.expand("9493401205").contains("+79493401205"))
    }

    @Test
    fun emailAddsItsLowerCasedForm() {
        assertEquals(
            listOf("Ivan.Petrov@Example.COM", "ivan.petrov@example.com"),
            InteractionSearchTerms.expand("Ivan.Petrov@Example.COM")
        )
    }

    @Test
    fun termsAreDistinctAndOrderedWithTheRawQueryFirst() {
        val terms = InteractionSearchTerms.expand("+79493401205")
        assertEquals(terms.distinct(), terms)
        assertEquals("+79493401205", terms.first())
    }
}
