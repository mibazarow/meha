package com.andharness.ru.data.agent

import com.andharness.ru.domain.agent.ToolResultEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truncation with an honest marker (TOOLS_TASKS §2): the cut lands on a line boundary, the model is
 * told exactly how much is hidden, and the `exit N` envelope survives.
 */
class ToolOutputLimiterTest {

    @Test
    fun shortOutputIsReturnedVerbatim() {
        val text = "короткий вывод"
        assertEquals(text, ToolOutputLimiter.limit(text))
    }

    @Test
    fun truncationCutsOnALineBoundaryAndReportsWhatIsHidden() {
        val lines = (1..5_000).joinToString("\n") { "строка номер $it с некоторым текстом" }
        val limited = ToolOutputLimiter.limit(lines)

        val markerStart = limited.indexOf("\n\n[вывод усечён")
        assertTrue("the marker must be present", markerStart > 0)

        val shown = limited.substring(0, markerStart)
        assertTrue("shown part must be non-empty", shown.isNotEmpty())
        assertTrue(
            "the shown part must end at a whole line",
            lines.startsWith(shown) && lines[shown.length] == '\n'
        )
        assertTrue("hidden characters must be reported", limited.contains("скрыто ещё"))
        assertTrue("hidden lines must be reported", limited.contains("строках"))
        assertTrue("the marker must say how to continue", limited.contains("offset"))
    }

    @Test
    fun aSingleHugeLineIsCutWithoutBreakingSurrogatePairs() {
        val emoji = "🙂".repeat(ToolOutputLimiter.MAX_CHARS)
        val limited = ToolOutputLimiter.limit(emoji)

        val markerStart = limited.indexOf("\n\n[вывод усечён")
        val shown = limited.substring(0, markerStart)
        val last = shown.last()
        assertTrue(
            "the last character must not be half of a surrogate pair",
            !Character.isHighSurrogate(last)
        )
        assertTrue(limited.contains("вывод усечён"))
    }

    @Test
    fun theExitEnvelopeSurvivesTruncation() {
        val body = (1..5_000).joinToString("\n") { "строка $it" }
        val limited = ToolOutputLimiter.limit(ToolResultEnvelope.wrap(false, body))

        assertEquals(1, ToolResultEnvelope.splitExit(limited).first)
        assertTrue(limited.contains("вывод усечён"))
    }
}
