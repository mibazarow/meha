package com.andharness.ru.data.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/** The style block must live in the volatile tail, after the profile and before recall evidence. */
class SystemPromptBuilderStyleBlockTest {

    private fun blocks(profile: String, style: String) = ContextBlockBuilder.Blocks(
        memoryBlock = "",
        deviceSummary = "",
        deviceFactsBlock = "",
        peopleBlock = "",
        trustedSourcesBlock = "",
        profileBlock = profile,
        mcpBlock = "",
        objectiveBlock = "",
        currentChatBlock = "",
        nowBlock = "",
        recallHintBlock = "",
        styleBlock = style,
    )

    @Test
    fun styleBlockIsRenderedAfterTheProfile() {
        val tail = SystemPromptBuilder.buildRuntimeTail(
            blocks(profile = "ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ:\n- [p1] зовут Алексей", style = "СТИЛЬ:\n- на «ты»"),
            language = "ru",
        )

        val profileIndex = tail.indexOf("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ")
        val styleIndex = tail.indexOf("СТИЛЬ:")
        assertTrue(profileIndex >= 0)
        assertTrue(styleIndex > profileIndex)
    }

    @Test
    fun emptyStyleBlockRendersNothing() {
        val tail = SystemPromptBuilder.buildRuntimeTail(blocks(profile = "", style = ""), language = "ru")

        assertTrue(!tail.contains("СТИЛЬ:"))
    }
}
