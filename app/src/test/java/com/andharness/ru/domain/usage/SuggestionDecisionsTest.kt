package com.andharness.ru.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the decision filter (update.md idea #16): ids the user accepted or dismissed must
 * never be offered again, and unrelated suggestions must be left untouched.
 */
class SuggestionDecisionsTest {

    private fun suggestion(id: String) = AutomationSuggestion(
        id = id,
        kind = SuggestionKind.APP_USAGE,
        title = id,
        description = id,
        confidence = 1.0,
        occurrences = 5,
        days = 5,
        observedDays = 5,
        hourOfDay = 20,
        packageName = id,
        triggerConfig = "20:00",
        actionConfig = "t | t"
    )

    @Test
    fun `dismissed and accepted ids are excluded`() {
        val all = listOf(suggestion("app:a:20"), suggestion("app:b:8"), suggestion("night:23"))

        val visible = SuggestionDecisions.visible(all, setOf("app:a:20", "night:23"))

        assertEquals(listOf("app:b:8"), visible.map { it.id })
    }

    @Test
    fun `empty decision set keeps everything in order`() {
        val all = listOf(suggestion("app:a:20"), suggestion("app:b:8"))

        val visible = SuggestionDecisions.visible(all, emptySet())

        assertEquals(all.map { it.id }, visible.map { it.id })
    }

    @Test
    fun `unknown ids in the store do not remove real suggestions`() {
        val all = listOf(suggestion("app:a:20"))

        val visible = SuggestionDecisions.visible(all, setOf("app:deleted:3", "night:1"))

        assertTrue(visible.map { it.id }.contains("app:a:20"))
    }
}
