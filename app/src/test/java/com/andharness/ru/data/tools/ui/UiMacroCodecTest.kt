package com.andharness.ru.data.tools.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure part of the UI-macro store ([UiMacroCodec]): JSON round-trip, the step and
 * macro caps, and corrupt-store degradation. [UiMacroStore] itself only adds Android
 * SharedPreferences I/O on top, and the handler (`UiAutomationToolsHandler`) is Android/accessibility
 * bound, so neither is exercised here.
 */
class UiMacroCodecTest {

    private fun step(i: Int) = UiMacroStep(tool = "ui_tap", argsJson = "{\"x\":\"$i\"}")

    private fun macro(id: String, name: String, steps: Int = 1) = UiMacroRecord(
        id = id,
        name = name,
        createdAt = 1_000L,
        steps = (1..steps).map { step(it) },
    )

    @Test
    fun roundTripPreservesFieldsAndOrder() {
        val records = listOf(macro("a", "Первый", 3), macro("b", "Второй", 2))
        assertEquals(records, UiMacroCodec.decode(UiMacroCodec.encode(records)))
    }

    @Test
    fun corruptOrMissingStoreDegradesToEmpty() {
        assertTrue(UiMacroCodec.decode(null).isEmpty())
        assertTrue(UiMacroCodec.decode("").isEmpty())
        assertTrue(UiMacroCodec.decode("this is not json").isEmpty())
        assertTrue(UiMacroCodec.decode("{\"unexpected\":true}").isEmpty())
    }

    @Test
    fun recordsWithoutIdOrNameAreDropped() {
        val raw = UiMacroCodec.encode(
            listOf(macro("", "Без id"), macro("ok", "   "), macro("good", "Нормальный"))
        )
        assertEquals(listOf("good"), UiMacroCodec.decode(raw).map { it.id })
    }

    @Test
    fun decodeClampsStepsToCapKeepingTheFirstSteps() {
        val raw = UiMacroCodec.encode(listOf(macro("big", "Длинный", UiMacroCodec.MAX_STEPS + 50)))
        val decoded = UiMacroCodec.decode(raw).single()
        assertEquals(UiMacroCodec.MAX_STEPS, decoded.steps.size)
        assertEquals("{\"x\":\"1\"}", decoded.steps.first().argsJson)
        assertEquals("{\"x\":\"${UiMacroCodec.MAX_STEPS}\"}", decoded.steps.last().argsJson)
    }

    @Test
    fun decodeKeepsNewestMacrosWhenOverCap() {
        val raw = UiMacroCodec.encode(
            (1..UiMacroCodec.MAX_MACROS + 10).map { macro("id$it", "macro$it") }
        )
        val decoded = UiMacroCodec.decode(raw)
        assertEquals(UiMacroCodec.MAX_MACROS, decoded.size)
        assertEquals("id${UiMacroCodec.MAX_MACROS + 10}", decoded.last().id)
    }

    @Test
    fun appendStopsAtStepCap() {
        val full = macro("full", "Полный", UiMacroCodec.MAX_STEPS)
        assertEquals(UiMacroCodec.MAX_STEPS, UiMacroCodec.append(full, step(999)).steps.size)

        val almost = macro("almost", "Почти", UiMacroCodec.MAX_STEPS - 1)
        val appended = UiMacroCodec.append(almost, step(999))
        assertEquals(UiMacroCodec.MAX_STEPS, appended.steps.size)
        assertEquals("{\"x\":\"999\"}", appended.steps.last().argsJson)
    }
}
