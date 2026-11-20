package com.andharness.ru.domain.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [TemplateRenderer]: `{{key}}`/`{{ key }}` substitution, honest reporting of
 * placeholders without a value, JSON value stringification and replacement parsing.
 *
 * References:
 * - kotlinx.serialization `JsonElement` —
 *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/
 */
class TemplateRendererTest {

    @Test
    fun substitutesBothPlaceholderSpellings() {
        val result = TemplateRenderer.render(
            "Привет, {{name}}! {{ city }} — {{name}}.",
            mapOf("name" to "Иван", "city" to "Москва"),
        )
        assertEquals("Привет, Иван! Москва — Иван.", result.text)
        assertEquals(listOf("name", "city"), result.applied)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun leavesUnknownPlaceholdersVerbatimAndReportsThem() {
        val result = TemplateRenderer.render(
            "Сумма: {{total}}, дата: {{ date }}",
            mapOf("total" to "100"),
        )
        assertEquals("Сумма: 100, дата: {{ date }}", result.text)
        assertEquals(listOf("date"), result.missing)
        assertEquals(listOf("total"), result.applied)
    }

    @Test
    fun missingKeyIsReportedOnlyOnce() {
        val result = TemplateRenderer.render("{{x}} {{x}}", emptyMap())
        assertEquals(listOf("x"), result.missing)
        assertEquals("{{x}} {{x}}", result.text)
    }

    @Test
    fun insertedValueIsLiteralEvenWithDollarSigns() {
        val result = TemplateRenderer.render("{{price}}", mapOf("price" to "\$1.99"))
        assertEquals("\$1.99", result.text)
    }

    @Test
    fun parsesAnObjectOfValuesAndPreservesOrder() {
        val values = TemplateRenderer.parseValues("""{"a": "1", "b": "2"}""")
        assertEquals(listOf("a", "b"), values.keys.toList())
        assertEquals("1", values["a"])
    }

    @Test
    fun rejectsNonObjectValues() {
        val error = runCatching { TemplateRenderer.parseValues("""["a"]""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsMalformedValuesJson() {
        val error = runCatching { TemplateRenderer.parseValues("{not json") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun templateReplacementsAreWrappedAsPlaceholders() {
        val map = TemplateRenderer.parseReplacements("""{"name": "Иван"}""", templateStyle = true)
        assertEquals(mapOf("{{name}}" to "Иван"), map)
    }

    @Test
    fun literalReplacementsKeepTheirKeys() {
        val map = TemplateRenderer.parseReplacements("""{"old": "new"}""", templateStyle = false)
        assertEquals(mapOf("old" to "new"), map)
    }

    @Test
    fun stringifiesPrimitivesNullArraysAndObjects() {
        // parseValues applies stringify to each value, so these assertions exercise it directly.
        assertEquals("42", TemplateRenderer.parseValues("""{"v":42}""")["v"])
        assertEquals("true", TemplateRenderer.parseValues("""{"v":true}""")["v"])
        assertEquals("", TemplateRenderer.parseValues("""{"v":null}""")["v"])
        assertEquals("1, 2", TemplateRenderer.parseValues("""{"v":[1,2]}""")["v"])
        assertEquals("a=1; b=x", TemplateRenderer.parseValues("""{"v":{"a":1,"b":"x"}}""")["v"])
    }
}
