package com.andharness.ru.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deterministic recall nudge must reach the model, but only on recall turns. */
class SystemPromptBuilderRecallHintTest {

    private fun blocks(hint: String) = ContextBlockBuilder.Blocks(
        memoryBlock = "",
        deviceSummary = "",
        deviceFactsBlock = "",
        peopleBlock = "",
        trustedSourcesBlock = "",
        profileBlock = "",
        mcpBlock = "",
        objectiveBlock = "",
        currentChatBlock = "",
        nowBlock = "",
        recallHintBlock = hint,
    )

    @Test
    fun recallHintIsRenderedInTheRuntimeTail() {
        val hint = "RECALL HINT (this turn): call memory_search first."
        val tail = SystemPromptBuilder.buildRuntimeTail(blocks(hint), language = "ru")
        assertTrue("the hint must be attached to the turn", tail.contains(hint))
    }

    @Test
    fun emptyHintRendersNothing() {
        val tail = SystemPromptBuilder.buildRuntimeTail(blocks(""), language = "ru")
        assertFalse(tail.contains("RECALL HINT"))
    }
}
