package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OverlayRequestClassifier] — the pure RU/EN cue match that decides whether a
 * floating-assistant request is about the current screen.
 *
 * The rule that matters most is the DEFAULT: anything without an explicit cue is NOT a screen
 * request, so an unrelated ask never has the screen read or leaked into its chat. The conservative
 * cases below (e.g. «переведи деньги» must not match because bare «переведи» is not a cue) pin that
 * down.
 */
class OverlayRequestClassifierTest {

    @Test
    fun russianScreenRequestsAreDetected() {
        listOf(
            "что на экране?",
            "посмотри на экран и скажи",
            "прочитай это",
            "прочти текст на экране",
            "переведи это",
            "переведи надпись",
            "переведи текст",
            "что тут написано?",
            "что здесь?",
            "сделай скриншот",
            "что за надпись внизу",
        ).forEach { text ->
            assertTrue("RU screen request must match: «$text»", OverlayRequestClassifier.isScreenRequest(text))
        }
    }

    @Test
    fun englishScreenRequestsAreDetected() {
        listOf(
            "what is on the screen?",
            "read this",
            "read that for me",
            "translate this",
            "translate that",
            "take a screenshot",
            "SCREEN",
        ).forEach { text ->
            assertTrue("EN screen request must match: «$text»", OverlayRequestClassifier.isScreenRequest(text))
        }
    }

    @Test
    fun unrelatedRequestsAreNotScreenRequests() {
        listOf(
            "поставь будильник на 7 утра",
            "позвони маме",
            "какая погода завтра?",
            // Bare «переведи» is deliberately NOT a cue: a money transfer is not a screen request.
            "переведи деньги на карту",
            "напиши стих о море",
            "включи фонарик",
            "суммаризируй новости",
            "hello world",
            "read a book",
            "summarize the news",
            // `screen` must match only as a whole word, so `screensaver` is not a screen request.
            "screensaver mode",
        ).forEach { text ->
            assertFalse("non-screen request must not match: «$text»", OverlayRequestClassifier.isScreenRequest(text))
        }
    }

    @Test
    fun blankTextDefaultsToNotScreenRequest() {
        assertFalse(OverlayRequestClassifier.isScreenRequest(""))
        assertFalse(OverlayRequestClassifier.isScreenRequest("   "))
        assertFalse(OverlayRequestClassifier.isScreenRequest("\n\t"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(OverlayRequestClassifier.isScreenRequest("ЧТО НА ЭКРАНЕ?"))
        assertTrue(OverlayRequestClassifier.isScreenRequest("Скриншот"))
        assertTrue(OverlayRequestClassifier.isScreenRequest("  READ THIS  "))
    }
}
