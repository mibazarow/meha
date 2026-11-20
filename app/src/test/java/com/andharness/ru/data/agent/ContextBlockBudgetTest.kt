package com.andharness.ru.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-block token budget (CONTEXT_TASKS_V2 §2): reference blocks get a share of the compaction
 * budget, are trimmed by ITEM COUNT (never mid-item), and the small fixed blocks are never touched.
 */
class ContextBlockBudgetTest {

    private fun memoryItems(count: Int): String =
        (1..count).joinToString("\n") { "- [$it] Пользователь любит чай номер $it (важность 2)" }

    private fun personItems(count: Int): String =
        (1..count).joinToString("\n") { "- #$it Человек $it (взаимодействий: 1): 01.01 12:00 SMS" }

    private fun profileItems(count: Int): String =
        (1..count).joinToString("\n") { "- [$it] Факт профиля номер $it" }

    private fun blocks(
        memoryBlock: String = "",
        profileBlock: String = "",
        peopleBlock: String = "",
        deviceSummary: String = "Model: TestPhone",
        deviceFactsBlock: String = "",
        mcpBlock: String = "",
        trustedSourcesBlock: String = "ДОВЕРЕННЫЕ ИСТОЧНИКИ: нет",
        nowBlock: String = "CURRENT DATE AND TIME",
        currentChatBlock: String = "ТЕКУЩИЙ ЧАТ: id=1.",
        objectiveBlock: String = "АКТИВНАЯ ЦЕЛЬ: тест",
        recallHintBlock: String = "RECALL HINT"
    ) = ContextBlockBuilder.Blocks(
        memoryBlock = memoryBlock,
        deviceSummary = deviceSummary,
        deviceFactsBlock = deviceFactsBlock,
        peopleBlock = peopleBlock,
        trustedSourcesBlock = trustedSourcesBlock,
        profileBlock = profileBlock,
        mcpBlock = mcpBlock,
        objectiveBlock = objectiveBlock,
        currentChatBlock = currentChatBlock,
        nowBlock = nowBlock,
        recallHintBlock = recallHintBlock
    )

    @Test
    fun aGenerousBudgetLeavesEveryBlockUntouched() {
        val original = blocks(
            memoryBlock = memoryItems(5),
            profileBlock = profileItems(3),
            peopleBlock = "ЛЮДИ / ПОСЛЕДНИЕ ВЗАИМОДЕЙСТВИЯ\nОбращайся к персональной базе.\n" +
                personItems(3),
            deviceFactsBlock = "ФАКТЫ УСТРОЙСТВА: батарея 80%",
            mcpBlock = "MCP: 2 сервера"
        )
        assertEquals(original, ContextBlockBudget.apply(original, 100_000L))
    }

    @Test
    fun aNonPositiveBudgetChangesNothing() {
        val original = blocks(memoryBlock = memoryItems(50))
        assertEquals(original, ContextBlockBudget.apply(original, 0L))
    }

    @Test
    fun memoryKeepsWholeItemsOnlyAndSaysHowManyAreHidden() {
        val original = blocks(memoryBlock = memoryItems(50))
        // 12% of 100 tokens = 12 tokens ≈ 24 Cyrillic chars — a couple of items at most.
        val trimmed = ContextBlockBudget.apply(original, 100L)

        val lines = trimmed.memoryBlock.lines().filter { it.startsWith("- [") }
        assertTrue("at least one memory must survive", lines.isNotEmpty())
        assertTrue("the block must be cut down", lines.size < 50)
        assertTrue(
            "no item may be truncated in the middle",
            lines.all { it.endsWith("(важность 2)") }
        )
        assertTrue(
            "the model must be told that entries are hidden",
            trimmed.memoryBlock.contains("записей не показано")
        )
    }

    @Test
    fun profileAndPeopleAreTrimmedByItemCountKeepingTheirHeader() {
        val original = blocks(
            profileBlock = "ПРОФИЛЬ:\n" + profileItems(40),
            peopleBlock = "ЛЮДИ / ПОСЛЕДНИЕ ВЗАИМОДЕЙСТВИЯ\nОбращайся к персональной базе.\n" +
                personItems(40)
        )
        // 2000 tokens: 8% = 160 for the profile, 6% = 120 for people — headers fit, items do not.
        val trimmed = ContextBlockBudget.apply(original, 2_000L)

        assertTrue(
            "the profile header must survive so the block stays readable",
            trimmed.profileBlock.startsWith("ПРОФИЛЬ:")
        )
        assertTrue(
            "the people header must survive so the block stays readable",
            trimmed.peopleBlock.startsWith("ЛЮДИ / ПОСЛЕДНИЕ ВЗАИМОДЕЙСТВИЯ")
        )
        assertTrue(
            "people lines are cut whole",
            trimmed.peopleBlock.lines().filter { it.startsWith("- #") }.all { it.contains("SMS") }
        )
        assertTrue(trimmed.profileBlock.lines().count { it.startsWith("- [") } < 40)
        assertTrue(trimmed.peopleBlock.lines().count { it.startsWith("- #") } < 40)
    }

    @Test
    fun smallFixedBlocksAreDroppedWholeWhenTheyDoNotFit() {
        val original = blocks(
            deviceSummary = "x".repeat(4_000),
            deviceFactsBlock = "y".repeat(4_000),
            mcpBlock = "z".repeat(4_000)
        )
        // 100 tokens: 4% = 4 tokens, 2% = 2 tokens — none of these fit.
        val trimmed = ContextBlockBudget.apply(original, 100L)
        assertEquals("", trimmed.deviceSummary)
        assertEquals("", trimmed.deviceFactsBlock)
        assertEquals("", trimmed.mcpBlock)
    }

    @Test
    fun theRecallBlockIsAlsoCappedInAbsoluteTokens() {
        // 12% of a huge window would allow thousands of tokens; the absolute ceiling is 3000.
        val original = blocks(memoryBlock = memoryItems(400))
        val trimmed = ContextBlockBudget.apply(original, 10_000_000L)

        assertTrue(
            "the recall block must respect the absolute ceiling",
            LlmTokenEstimator.estimate(trimmed.memoryBlock) <=
                ContextBlockBudget.MAX_MEMORY_BLOCK_TOKENS
        )
        assertTrue(trimmed.memoryBlock.contains("записей не показано"))
    }

    @Test
    fun fixedBlocksAreNeverTouched() {
        val original = blocks(recallHintBlock = "RECALL HINT " + "x".repeat(2_000))
        val trimmed = ContextBlockBudget.apply(original, 10L)
        assertEquals(original.trustedSourcesBlock, trimmed.trustedSourcesBlock)
        assertEquals(original.nowBlock, trimmed.nowBlock)
        assertEquals(original.currentChatBlock, trimmed.currentChatBlock)
        assertEquals(original.objectiveBlock, trimmed.objectiveBlock)
        assertEquals(original.recallHintBlock, trimmed.recallHintBlock)
        assertFalse(trimmed.recallHintBlock.isBlank())
    }
}
