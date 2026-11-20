package com.andharness.ru.data.osint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [NumberNormalizer] (update.md §2.4): no Android runtime, no network.
 * Reference: https://developer.android.com/reference/java/lang/Character#isDigit(int)
 */
class NumberNormalizerTest {

    @Test
    fun normalizesRussianFormatsToSameValue() {
        val expected = "+79991234567"
        assertEquals(expected, NumberNormalizer.normalize("+7 (999) 123-45-67"))
        assertEquals(expected, NumberNormalizer.normalize("8 999 123 45 67"))
        assertEquals(expected, NumberNormalizer.normalize("8-999-123-45-67"))
        assertEquals(expected, NumberNormalizer.normalize("9991234567"))
        assertEquals(expected, NumberNormalizer.normalize("  +79991234567  "))
    }

    @Test
    fun normalizesInternationalPrefixAndForeignNumbers() {
        assertEquals("+442012345678", NumberNormalizer.normalize("00 44 20 1234 5678"))
        assertEquals("+12025550147", NumberNormalizer.normalize("+1 (202) 555-0147"))
        assertEquals("+380441234567", NumberNormalizer.normalize("+380 44 123 4567"))
    }

    @Test
    fun rejectsAlphanumericSendersAndGarbage() {
        assertNull(NumberNormalizer.normalize(null))
        assertNull(NumberNormalizer.normalize(""))
        assertNull(NumberNormalizer.normalize("   "))
        assertNull(NumberNormalizer.normalize("SBERBANK"))
        assertNull(NumberNormalizer.normalize("UNKNOWN"))
        assertNull(NumberNormalizer.normalize("123"))
        assertNull(NumberNormalizer.normalize("+0123456"))
        assertNull(NumberNormalizer.normalize("+1234567890123456789"))
    }

    @Test
    fun regionHeuristicIsHonestAboutUnknownPrefixes() {
        assertTrue(NumberNormalizer.regionFor("+79991234567")!!.contains("Россия"))
        assertTrue(NumberNormalizer.regionFor("+12025550147")!!.contains("Северная Америка"))
        assertNull(NumberNormalizer.regionFor("+999999999999"))
    }

    @Test
    fun isNormalizedAcceptsOnlyCanonicalValues() {
        assertTrue(NumberNormalizer.isNormalized("+79991234567"))
        assertFalse(NumberNormalizer.isNormalized("79991234567"))
        assertFalse(NumberNormalizer.isNormalized("+7 999"))
        assertFalse(NumberNormalizer.isNormalized(null))
    }
}
