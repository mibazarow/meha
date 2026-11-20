package com.andharness.ru.data.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure helpers behind `remember_screen`: text flattening/dedupe/bounding and the
 * memory note text/tags. Android-free, so no device or accessibility service is needed.
 */
class ScreenRememberSupportTest {

    // ---- flatten ----

    @Test
    fun flattenTrimsDropsBlanksDedupesConsecutiveAndKeepsOrder() {
        val result = ScreenRememberSupport.flatten(
            listOf("  Привет  ", "", null, "Привет", "  ", "Мир", "Мир", "Пока"),
            maxChars = 4000,
        )
        assertEquals("Привет\nМир\nПока", result.text)
        assertEquals(3, result.lines)
        assertFalse(result.truncated)
    }

    @Test
    fun flattenKeepsNonConsecutiveDuplicate() {
        val result = ScreenRememberSupport.flatten(
            listOf("A", "B", "A"),
            maxChars = 4000,
        )
        assertEquals("A\nB\nA", result.text)
    }

    @Test
    fun flattenBoundsToMaxCharsAndFlagsTruncation() {
        val result = ScreenRememberSupport.flatten(
            listOf("1234567890", "abcdefghij", "ZZZZZ"),
            maxChars = 15,
        )
        assertEquals(15, result.text.length)
        assertEquals("1234567890\nabcd", result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun flattenExactFitIsNotTruncated() {
        val result = ScreenRememberSupport.flatten(
            listOf("one", "two"),
            maxChars = 7,
        )
        assertEquals("one\ntwo", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun flattenAllBlankYieldsEmptyAndNotTruncated() {
        val result = ScreenRememberSupport.flatten(listOf("", "   ", null), maxChars = 4000)
        assertEquals("", result.text)
        assertEquals(0, result.lines)
        assertFalse(result.truncated)
    }

    // ---- parseMaxChars ----

    @Test
    fun parseMaxCharsDefaultsAndClamps() {
        assertEquals(ScreenRememberSupport.DEFAULT_MAX_CHARS, ScreenRememberSupport.parseMaxChars(null))
        assertEquals(ScreenRememberSupport.DEFAULT_MAX_CHARS, ScreenRememberSupport.parseMaxChars("  "))
        assertEquals(ScreenRememberSupport.DEFAULT_MAX_CHARS, ScreenRememberSupport.parseMaxChars("abc"))
        assertEquals(ScreenRememberSupport.MIN_MAX_CHARS, ScreenRememberSupport.parseMaxChars("1"))
        assertEquals(ScreenRememberSupport.MAX_MAX_CHARS, ScreenRememberSupport.parseMaxChars("999999"))
        assertEquals(1234, ScreenRememberSupport.parseMaxChars("1234"))
    }

    // ---- buildNoteText ----

    @Test
    fun buildNoteTextPrefixesOnlyWhenNotePresent() {
        assertEquals("Заголовок: текст", ScreenRememberSupport.buildNoteText("Заголовок", "текст"))
        assertEquals("текст", ScreenRememberSupport.buildNoteText(null, "текст"))
        assertEquals("текст", ScreenRememberSupport.buildNoteText("   ", "текст"))
    }

    // ---- buildTags ----

    @Test
    fun buildTagsAlwaysAddsRememberTagAndScreenPackage() {
        val tags = ScreenRememberSupport.buildTags(
            userTags = null,
            packageName = "org.telegram.messenger",
            personId = null,
        )
        assertEquals("remember_screen,screen:org.telegram.messenger", tags)
    }

    @Test
    fun buildTagsAddsPersonAndUserTagsDeduped() {
        val tags = ScreenRememberSupport.buildTags(
            userTags = "важное, remember_screen, screen:org.telegram.messenger",
            packageName = "org.telegram.messenger",
            personId = 42L,
        )
        assertEquals(
            "remember_screen,screen:org.telegram.messenger,person:42,важное",
            tags,
        )
    }

    @Test
    fun buildTagsOmitsBlankPackageAndPerson() {
        val tags = ScreenRememberSupport.buildTags(userTags = " , x ,", packageName = "  ", personId = null)
        assertEquals("remember_screen,x", tags)
    }
}
