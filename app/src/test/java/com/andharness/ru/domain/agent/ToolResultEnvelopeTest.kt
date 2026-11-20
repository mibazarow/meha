package com.andharness.ru.domain.agent

import com.andharness.ru.data.agent.ToolOutputLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Machine-readable `exit N` envelope for tool results (TOOLS_TASKS §1). */
class ToolResultEnvelopeTest {

    @Test
    fun successAndFailureRenderTheExitLineFirst() {
        assertEquals("exit 0\nФайл записан", ToolResultEnvelope.wrap(true, "Файл записан"))
        assertEquals("exit 1\nНет доступа", ToolResultEnvelope.wrap(false, "Нет доступа"))
    }

    @Test
    fun splitExitRoundTripsAndPreservesMultilineBodies() {
        val text = ToolResultEnvelope.wrap(false, "строка 1\nстрока 2")

        val (code, body) = ToolResultEnvelope.splitExit(text)
        assertEquals(1, code)
        assertEquals("строка 1\nстрока 2", body)
    }

    @Test
    fun textWithoutAnEnvelopeIsReturnedUntouched() {
        val (code, body) = ToolResultEnvelope.splitExit("просто текст\nexit 0 в середине")
        assertNull("a raw text has no exit code", code)
        assertEquals("просто текст\nexit 0 в середине", body)

        val (proseCode, proseBody) = ToolResultEnvelope.splitExit("exit code: 5")
        assertNull("prose that merely starts with 'exit' must not be parsed as an envelope", proseCode)
        assertEquals("exit code: 5", proseBody)
    }

    @Test
    fun truncationNeverCutsTheExitLine() {
        val body = "x".repeat(ToolOutputLimiter.MAX_CHARS * 2)
        val limited = ToolOutputLimiter.limit(ToolResultEnvelope.wrap(false, body))

        assertTrue("the envelope must stay on the first line", limited.startsWith("exit 1\n"))
        assertTrue("the body must be bounded", limited.length < body.length)
        assertTrue("the truncation must be announced", limited.contains("вывод усечён"))
        val (code, rest) = ToolResultEnvelope.splitExit(limited)
        assertEquals(1, code)
        assertTrue("the marker belongs to the body, not to the envelope", rest.contains("вывод усечён"))
    }

    @Test
    fun truncationOfRawTextStillWorks() {
        val limited = ToolOutputLimiter.limit("y".repeat(ToolOutputLimiter.MAX_CHARS + 10))

        assertTrue(limited.contains("вывод усечён"))
        assertNull(ToolResultEnvelope.splitExit(limited).first)
    }
}
