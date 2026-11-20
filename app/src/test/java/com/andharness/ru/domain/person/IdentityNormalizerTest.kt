package com.andharness.ru.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [IdentityNormalizer] (update.md §4.3, "правило нормализации"): no Android
 * runtime, no network.
 *
 * Official reference for the digit handling:
 * https://developer.android.com/reference/java/lang/Character#isDigit(int)
 */
class IdentityNormalizerTest {

    // ---- phone ----

    @Test
    fun phoneVariantsNormalizeToTheSameE164LikeValue() {
        val expected = "+79991234567"
        assertEquals(expected, IdentityNormalizer.normalizePhone("+7 (999) 123-45-67"))
        assertEquals(expected, IdentityNormalizer.normalizePhone("8 999 123 45 67"))
        assertEquals(expected, IdentityNormalizer.normalizePhone("8-999-123-45-67"))
        assertEquals(expected, IdentityNormalizer.normalizePhone("9991234567"))
        assertEquals(expected, IdentityNormalizer.normalizePhone("  +79991234567  "))
    }

    @Test
    fun phoneHandlesInternationalPrefixAndForeignNumbers() {
        assertEquals("+442012345678", IdentityNormalizer.normalizePhone("00 44 20 1234 5678"))
        assertEquals("+12025550147", IdentityNormalizer.normalizePhone("+1 (202) 555-0147"))
        assertEquals("+380441234567", IdentityNormalizer.normalizePhone("+380 44 123 4567"))
    }

    @Test
    fun phoneRejectsGarbageAndAlphanumericSenders() {
        assertNull(IdentityNormalizer.normalizePhone(null))
        assertNull(IdentityNormalizer.normalizePhone(""))
        assertNull(IdentityNormalizer.normalizePhone("   "))
        assertNull(IdentityNormalizer.normalizePhone("SBERBANK"))
        assertNull(IdentityNormalizer.normalizePhone("UNKNOWN"))
        assertNull(IdentityNormalizer.normalizePhone("123"))
        assertNull(IdentityNormalizer.normalizePhone("+0123456"))
        assertNull(IdentityNormalizer.normalizePhone("+1234567890123456789"))
    }

    @Test
    fun phoneMatchKeyIsTheLastTenSignificantDigits() {
        assertEquals("9991234567", IdentityNormalizer.phoneMatchKey("+7 (999) 123-45-67"))
        assertEquals("9991234567", IdentityNormalizer.phoneMatchKey("8 999 123 45 67"))
        assertEquals("2025550147", IdentityNormalizer.phoneMatchKey("+1 (202) 555-0147"))
        // Shorter-than-national numbers are returned as-is instead of being padded or guessed.
        assertEquals("12345", IdentityNormalizer.phoneMatchKey("12345"))
        assertNull(IdentityNormalizer.phoneMatchKey("SBERBANK"))
        assertNull(IdentityNormalizer.phoneMatchKey(null))
    }

    // ---- email ----

    @Test
    fun emailIsTrimmedAndLowerCased() {
        assertEquals(
            "ivan.petrov@example.com",
            IdentityNormalizer.normalizeEmail("  Ivan.Petrov@Example.COM ")
        )
    }

    @Test
    fun emailRejectsStructurallyInvalidValues() {
        assertNull(IdentityNormalizer.normalizeEmail(null))
        assertNull(IdentityNormalizer.normalizeEmail(""))
        assertNull(IdentityNormalizer.normalizeEmail("   "))
        assertNull(IdentityNormalizer.normalizeEmail("no-at-sign"))
        assertNull(IdentityNormalizer.normalizeEmail("a@b"))
        assertNull(IdentityNormalizer.normalizeEmail("a@@b.com"))
        assertNull(IdentityNormalizer.normalizeEmail("a b@c.com"))
        assertNull(IdentityNormalizer.normalizeEmail("@x.com"))
        assertNull(IdentityNormalizer.normalizeEmail("a@.com"))
        assertNull(IdentityNormalizer.normalizeEmail("a@b..com"))
        assertNull(IdentityNormalizer.normalizeEmail("a@b."))
    }

    // ---- messenger / other ----

    @Test
    fun messengerIdIsTrimmedButKeepsItsCase() {
        assertEquals("@Serega", IdentityNormalizer.normalizeMessenger("  @Serega  "))
        assertEquals("user", IdentityNormalizer.normalizeMessenger("user"))
        assertNull(IdentityNormalizer.normalizeMessenger("   "))
        assertNull(IdentityNormalizer.normalizeMessenger(null))
    }

    @Test
    fun otherIdentityIsJustTrimmed() {
        assertEquals("x", IdentityNormalizer.normalize(IdentityType.OTHER, "  x  "))
        assertNull(IdentityNormalizer.normalize(IdentityType.OTHER, "  "))
    }

    // ---- entry point ----

    @Test
    fun entryPointDispatchesByTypeAndIgnoresThePackageForTheValue() {
        assertEquals("+79991234567", IdentityNormalizer.normalize(IdentityType.PHONE, "8 999 123 45 67"))
        assertEquals("a@b.com", IdentityNormalizer.normalize(IdentityType.EMAIL, " A@B.com "))
        assertEquals(
            "user",
            IdentityNormalizer.normalize(
                IdentityType.MESSENGER,
                "  user ",
                appPackage = "org.telegram.messenger"
            )
        )
        assertNull(IdentityNormalizer.normalize(IdentityType.PHONE, "not-a-number"))
        assertEquals("", IdentityNormalizer.NO_PACKAGE)
    }
}
