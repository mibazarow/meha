package com.andharness.ru.domain.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure gating decision shared by the recall query path and the background embedding worker
 * ([SemanticSearchGate]): the bundled on-device embedder is touched ONLY when semantic search is
 * enabled AND there is real text to embed. When the switch is off, or the text is blank ⇒ no work at all, so
 * recall stays exactly the previous lexical BM25 path.
 */
class SemanticSearchGateTest {

    @Test
    fun disabledSwitchNeverEmbedsEvenWithText() {
        assertFalse(SemanticSearchGate.shouldEmbed(enabled = false, text = "любой текст"))
    }

    @Test
    fun enabledSwitchEmbedsNonBlankText() {
        assertTrue(SemanticSearchGate.shouldEmbed(enabled = true, text = "налоговая декларация"))
    }

    @Test
    fun enabledSwitchSkipsEmptyAndWhitespaceOnlyText() {
        assertFalse(SemanticSearchGate.shouldEmbed(enabled = true, text = ""))
        assertFalse(SemanticSearchGate.shouldEmbed(enabled = true, text = "   \n\t "))
    }

    @Test
    fun disabledSwitchSkipsBlankTextToo() {
        assertFalse(SemanticSearchGate.shouldEmbed(enabled = false, text = ""))
    }
}
