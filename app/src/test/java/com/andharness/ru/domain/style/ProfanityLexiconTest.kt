package com.andharness.ru.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfanityLexiconTest {

    @Test
    fun cleanTextIsClean() {
        val signal = ProfanityLexicon.analyze("сегодня хорошая погода, пойдём гулять")
        assertFalse(signal.mild)
        assertFalse(signal.isHostile)
        assertEquals(0, signal.styleHits)
    }

    @Test
    fun mildSwearingIsStyleEligible() {
        val signal = ProfanityLexicon.analyze("бля, ну нахуй это делать")
        assertTrue(signal.mild)
        assertFalse(signal.isHostile)
        assertEquals(1, signal.styleHits)
    }

    @Test
    fun obfuscatedAndLatinFormsAreRecognized() {
        listOf("х*й", "б л я", "п-и-з-д-е-ц", "xуй", "6лядь").forEach { text ->
            assertTrue("expected mild: $text", ProfanityLexicon.analyze(text).mild)
        }
    }

    @Test
    fun insultsSuppressMildCounting() {
        val signal = ProfanityLexicon.analyze("ты мудак, бля")
        assertTrue(signal.insult)
        assertTrue(signal.isHostile)
        assertEquals(0, signal.styleHits)
    }

    @Test
    fun slursAreAlwaysHostile() {
        val signal = ProfanityLexicon.analyze("пидор")
        assertTrue(signal.slur)
        assertTrue(signal.isHostile)
        assertEquals(0, signal.styleHits)
    }

    @Test
    fun knownFalsePositivesAreIgnored() {
        listOf("хулиган", "бляха", "себя", "себе", "жидкость").forEach { text ->
            assertFalse("expected clean: $text", ProfanityLexicon.analyze(text).mild)
        }
    }

    @Test
    fun shortInsultWordsAreMatched() {
        listOf("лох", "чмо", "дурак").forEach { text ->
            assertTrue("expected insult: $text", ProfanityLexicon.containsHardLanguage(text))
        }
    }

    @Test
    fun blankTextIsClean() {
        assertFalse(ProfanityLexicon.analyze("   ").mild)
        assertEquals(0, ProfanityLexicon.count(""))
    }
}
