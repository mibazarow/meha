package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OverlayQuickAsk] — the quick-ask panel state machine.
 *
 * The rules that matter for the user: a blank draft never sends, a submit in flight cannot be sent
 * twice, and a failed hand-off keeps the typed text so nothing has to be retyped.
 */
class OverlayQuickAskTest {

    @Test
    fun panelStartsCollapsedAndEmpty() {
        val state = OverlayQuickAskState()
        assertFalse(state.isExpanded)
        assertFalse(OverlayQuickAsk.canSubmit(state))
        assertNull(OverlayQuickAsk.payload(state))
    }

    @Test
    fun expandOpensThePanelAndClearsAnError() {
        val failed = OverlayQuickAskState(input = "привет", isExpanded = true, error = "boom")
        val expanded = OverlayQuickAsk.expand(failed)
        assertTrue(expanded.isExpanded)
        assertNull(expanded.error)
        assertEquals("привет", expanded.input)
    }

    @Test
    fun collapseForgetsTheDraft() {
        val state = OverlayQuickAskState(input = "черновик", isExpanded = true)
        assertEquals(OverlayQuickAskState(), OverlayQuickAsk.collapse())
        assertFalse(OverlayQuickAsk.collapse().isExpanded)
        assertEquals("черновик", state.input)
    }

    @Test
    fun blankInputCannotBeSubmitted() {
        val expanded = OverlayQuickAsk.expand(OverlayQuickAskState())
        assertFalse(OverlayQuickAsk.canSubmit(expanded))
        assertFalse(OverlayQuickAsk.canSubmit(OverlayQuickAsk.onInput(expanded, "   ")))
        assertNull(OverlayQuickAsk.payload(OverlayQuickAsk.onInput(expanded, "   ")))
    }

    @Test
    fun aCollapsedPanelNeverSubmitsEvenWithText() {
        val collapsed = OverlayQuickAskState(input = "текст")
        assertFalse(OverlayQuickAsk.canSubmit(collapsed))
    }

    @Test
    fun nonBlankInputCanBeSubmittedAndIsTrimmed() {
        val state = OverlayQuickAsk.onInput(OverlayQuickAsk.expand(OverlayQuickAskState()), "  задача  ")
        assertTrue(OverlayQuickAsk.canSubmit(state))
        assertEquals("задача", OverlayQuickAsk.payload(state))
    }

    @Test
    fun typingClearsAPreviousError() {
        val failed = OverlayQuickAsk.failed(OverlayQuickAsk.expand(OverlayQuickAskState()), "нет сети")
        assertEquals("нет сети", failed.error)
        assertNull(OverlayQuickAsk.onInput(failed, "ещё раз").error)
    }

    @Test
    fun sendingBlocksASecondSubmitUntilItFinishes() {
        val ready = OverlayQuickAsk.onInput(OverlayQuickAsk.expand(OverlayQuickAskState()), "вопрос")
        val inFlight = OverlayQuickAsk.sending(ready)
        assertTrue(inFlight.isSending)
        assertFalse(OverlayQuickAsk.canSubmit(inFlight))
    }

    @Test
    fun failureKeepsTheTextAndStopsTheSpinner() {
        val ready = OverlayQuickAsk.onInput(OverlayQuickAsk.expand(OverlayQuickAskState()), "вопрос")
        val failed = OverlayQuickAsk.failed(OverlayQuickAsk.sending(ready), "Ошибка БД")
        assertFalse(failed.isSending)
        assertEquals("вопрос", failed.input)
        assertEquals("Ошибка БД", failed.error)
        assertTrue(OverlayQuickAsk.canSubmit(failed))
    }

    @Test
    fun successClosesThePanelAndDropsTheDraft() {
        val ready = OverlayQuickAsk.onInput(OverlayQuickAsk.expand(OverlayQuickAskState()), "вопрос")
        assertEquals(OverlayQuickAskState(), OverlayQuickAsk.sent())
        assertEquals("вопрос", ready.input)
    }
}
