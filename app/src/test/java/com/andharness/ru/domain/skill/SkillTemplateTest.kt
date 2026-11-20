package com.andharness.ru.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure `{parameter}` renderer used by skill steps. Covers substitution, Cyrillic
 * parameter names, repeated placeholders, and the honest "missing parameter" failure.
 */
class SkillTemplateTest {

    @Test
    fun rendersSimplePlaceholder() {
        assertEquals(
            "Заказать такси до Ленина, 1",
            SkillTemplate.renderTemplate("Заказать такси до {адрес}", mapOf("адрес" to "Ленина, 1")),
        )
    }

    @Test
    fun rendersMultipleAndRepeatedPlaceholders() {
        val values = mapOf("q" to "кофе", "city" to "Казань")
        assertEquals(
            "https://site/Казань?q=кофе&again=кофе",
            SkillTemplate.renderTemplate("https://site/{city}?q={q}&again={q}", values),
        )
    }

    @Test
    fun missingParameterReturnsNull() {
        assertNull(SkillTemplate.renderTemplate("Привет, {имя}!", emptyMap()))
        assertNull(SkillTemplate.renderTemplate("Привет, {имя}!", mapOf("другое" to "x")))
    }

    @Test
    fun missingParametersAreListedHonestly() {
        assertEquals(
            listOf("имя", "адрес"),
            SkillTemplate.missingParameters("Привет, {имя}! Едешь в {адрес}, {имя}?", mapOf("x" to "y")),
        )
        assertTrue(SkillTemplate.missingParameters("без плейсхолдеров", emptyMap()).isEmpty())
    }

    @Test
    fun placeholderValuesAreNotReScanned() {
        // A substituted value that itself looks like a placeholder must not be expanded again.
        assertEquals(
            "{b}",
            SkillTemplate.renderTemplate("{a}", mapOf("a" to "{b}")),
        )
    }

    @Test
    fun escapeStyleFallbackReturnsProvidedDefault() {
        assertEquals(
            "нет значения",
            SkillTemplate.renderTemplate("Значение: {x}", emptyMap(), fallback = "нет значения"),
        )
    }

    @Test
    fun emptyValuePresentIsSubstitutedAsEmpty() {
        assertEquals("Значение: ", SkillTemplate.renderTemplate("Значение: {x}", mapOf("x" to "")))
    }

    @Test
    fun placeholdersAreDistinctInFirstAppearanceOrder() {
        assertEquals(
            listOf("a", "b"),
            SkillTemplate.placeholders("{a} {b} {a}"),
        )
    }

    @Test
    fun isSatisfiedReflectsPresenceOfKeys() {
        assertTrue(SkillTemplate.isSatisfied("{a}", mapOf("a" to "1")))
        assertTrue(!SkillTemplate.isSatisfied("{a}", emptyMap()))
    }
}
