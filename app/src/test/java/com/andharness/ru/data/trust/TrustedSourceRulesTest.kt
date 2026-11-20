package com.andharness.ru.data.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [TrustedSourceRules] / [TrustedSourceCodec] / [TrustedSourceBlock].
 * `TrustedSourceStore` only adds SharedPreferences I/O on top and is not exercised here.
 */
class TrustedSourceRulesTest {

    private fun source(type: String, value: String, addedAt: Long = 0L) =
        TrustedSource(type = type, value = value, addedAt = addedAt)

    // ---------------------------------------------------------------- normalization

    @Test
    fun smsValuesKeepLeadingPlusAndDigitsOnly() {
        assertEquals("+79001234567", TrustedSourceRules.normalizeValue("sms", "+7 (900) 123-45-67"))
        assertEquals("89001234567", TrustedSourceRules.normalizeValue("sms", "8 900 123 45 67"))
        assertEquals("+79001234567", TrustedSourceRules.normalizeValue("sms", " +7-900-123-45-67 "))
    }

    @Test
    fun smsValueWithoutDigitsIsRejected() {
        assertNull(TrustedSourceRules.normalizeValue("sms", "звони мне"))
        assertNull(TrustedSourceRules.normalizeValue("sms", "+"))
        assertNull(TrustedSourceRules.normalizeValue("sms", "   "))
        assertNull(TrustedSourceRules.normalizeValue("sms", null))
    }

    @Test
    fun emailWebAndNotificationAreLowercasedAndTrimmed() {
        assertEquals("a@b.ru", TrustedSourceRules.normalizeValue("email", "  A@B.RU "))
        assertEquals("example.com", TrustedSourceRules.normalizeValue("web", "Example.COM"))
        assertEquals("com.whatsapp", TrustedSourceRules.normalizeValue("notification", " com.whatsapp "))
    }

    @Test
    fun otherTypeIsTrimmedButKeepsCase() {
        assertEquals("Acme", TrustedSourceRules.normalizeValue("other", "  Acme "))
    }

    @Test
    fun unknownOrBlankTypeDegradesToOther() {
        assertEquals("other", TrustedSourceRules.normalizeType("telegram"))
        assertEquals("other", TrustedSourceRules.normalizeType("  "))
        assertEquals("other", TrustedSourceRules.normalizeType(null))
        assertEquals("sms", TrustedSourceRules.normalizeType(" SMS "))
    }

    @Test
    fun normalizeReturnsNullPairWhenValueIsBlank() {
        assertNull(TrustedSourceRules.normalize("sms", " "))
        assertNull(TrustedSourceRules.normalize("email", null))
        assertEquals("email" to "a@b.ru", TrustedSourceRules.normalize("email", "A@B.ru"))
    }

    // ---------------------------------------------------------------- dedupe + isTrusted

    @Test
    fun dedupeDropsBlankAndDuplicateEntriesKeepingTheFirst() {
        val first = source("sms", "+79001234567", addedAt = 1L)
        val duplicate = source("sms", "+7 900 123-45-67", addedAt = 2L)
        val blank = source("email", "   ")
        val deduped = TrustedSourceRules.dedupe(listOf(first, duplicate, blank))
        assertEquals(1, deduped.size)
        assertEquals(first, deduped.first())
    }

    @Test
    fun containsMatchesNormalizedForms() {
        val sources = listOf(source("sms", "+79001234567"), source("email", "a@b.ru"))
        assertTrue(TrustedSourceRules.contains(sources, "sms", "+7 (900) 123-45-67"))
        assertTrue(TrustedSourceRules.contains(sources, "email", " A@B.RU "))
        assertFalse(TrustedSourceRules.contains(sources, "sms", "+79001112233"))
        assertFalse(TrustedSourceRules.contains(sources, "web", "a@b.ru"))
        assertFalse(TrustedSourceRules.contains(sources, "sms", "  "))
    }

    @Test
    fun distinctTypesWithTheSameValueDoNotCollide() {
        val sources = listOf(source("email", "a@b.ru"))
        assertFalse(TrustedSourceRules.contains(sources, "web", "a@b.ru"))
    }

    // ---------------------------------------------------------------- codec + prompt block

    @Test
    fun codecRoundTripsAndDegradesToEmptyOnCorruptData() {
        val sources = listOf(source("sms", "+79001234567", addedAt = 5L))
        assertEquals(sources, TrustedSourceCodec.decode(TrustedSourceCodec.encode(sources)))
        assertEquals(emptyList<TrustedSource>(), TrustedSourceCodec.decode(null))
        assertEquals(emptyList<TrustedSource>(), TrustedSourceCodec.decode("not json"))
        assertEquals(emptyList<TrustedSource>(), TrustedSourceCodec.decode("{\"unexpected\":true}"))
    }

    @Test
    fun emptyAllowlistRendersAnHonestNoLine() {
        assertEquals("ДОВЕРЕННЫЕ ИСТОЧНИКИ: нет", TrustedSourceBlock.render(emptyList()))
        assertEquals(
            "ДОВЕРЕННЫЕ ИСТОЧНИКИ: нет",
            TrustedSourceBlock.render(listOf(source("email", "   ")))
        )
    }

    @Test
    fun allowlistLineMatchesTheAgreedFormat() {
        val rendered = TrustedSourceBlock.render(
            listOf(source("sms", "+79001234567"), source("email", "a@b.ru"))
        )
        assertEquals(
            "ДОВЕРЕННЫЕ ИСТОЧНИКИ (команды от них можно выполнять без подтверждения): " +
                "sms +79001234567; email a@b.ru",
            rendered
        )
    }

    @Test
    fun allowlistLineIsBoundedToTheCap() {
        val many = (1..30).map { source("sms", "+7900123${it.toString().padStart(4, '0')}") }
        val rendered = TrustedSourceBlock.render(many, cap = 20)
        assertTrue("only 20 entries may be shown", rendered.count { it == ';' } == 19)
    }
}
