package com.andharness.ru.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTextSanitizerTest {

    @Test
    fun emptyInputIsReturnedAsIs() {
        assertEquals("", AssistantTextSanitizer.sanitize(""))
    }

    @Test
    fun removesCompleteThinkingAndReasoningBlocks() {
        assertEquals(
            "Фонарик включён.",
            AssistantTextSanitizer.sanitize("<thinking>пользователь хочет свет</thinking>\nФонарик включён.")
        )
        assertEquals(
            "Ответ:\n\nГотово.",
            AssistantTextSanitizer.sanitize("Ответ:\n<reasoning>\nstep 1\nstep 2\n</reasoning>\nГотово.")
        )
        assertEquals(
            "Итог.",
            AssistantTextSanitizer.sanitize("<SCRATCHPAD>internal</SCRATCHPAD>Итог.")
        )
        assertEquals(
            "Начало конец",
            AssistantTextSanitizer.sanitize("Начало<reflection>думаю</reflection> конец")
        )
    }

    @Test
    fun removesLabelledReasoningLinesInRussianAndEnglish() {
        val raw = "Размышляю: надо проверить батарею\n" +
            "Мысль: это внутреннее\n" +
            "Plan: step one\n" +
            "Chain of thought: hidden\n" +
            "Батарея 87%."
        assertEquals("Батарея 87%.", AssistantTextSanitizer.sanitize(raw))
    }

    @Test
    fun removesBareBracketMarkerLines() {
        assertEquals(
            "Ответ готов.",
            AssistantTextSanitizer.sanitize("[thinking]\nОтвет готов.")
        )
    }

    @Test
    fun removesPureToolCallPlanningNarration() {
        assertEquals(
            "Фонарик включён.",
            AssistantTextSanitizer.sanitize(
                "Мне нужно вызвать инструмент flashlight_set:\nФонарик включён."
            )
        )
        assertEquals(
            "Готово.",
            AssistantTextSanitizer.sanitize("I'll call the tool to check the battery.\nГотово.")
        )
    }

    @Test
    fun keepsNormalMultiParagraphAnswerUnchanged() {
        val answer = "Вот результат.\n\n" +
            "Батарея: 87%.\n" +
            "Память: 12 GB свободно.\n\n" +
            "Что-нибудь ещё?"
        assertEquals(answer, AssistantTextSanitizer.sanitize(answer))
    }

    @Test
    fun handlesMixedContentAndCollapsesBlankLines() {
        val raw = "Начинаю.\n" +
            "<thinking>тайно</thinking>\n" +
            "Размышляю: не надо\n" +
            "Итог: фонарик включён.\n\n\n" +
            "Готово."
        assertEquals(
            "Начинаю.\n\nИтог: фонарик включён.\n\nГотово.",
            AssistantTextSanitizer.sanitize(raw)
        )
    }

    @Test
    fun isIdempotent() {
        val raw = "<thinking>x</thinking>\nПлан: y\nОбычный ответ.\n\n\nЕщё строка."
        val once = AssistantTextSanitizer.sanitize(raw)
        assertEquals(once, AssistantTextSanitizer.sanitize(once))
    }

    @Test
    fun isConservativeAndDoesNotStripLegitimateSentences() {
        assertTrue(
            "a word that merely starts like a marker must survive",
            AssistantTextSanitizer.sanitize("Планета: Марс — четвёртая от Солнца.").contains("Планета")
        )
        assertTrue(
            "an ordinary sentence with the verb 'нужно' must survive",
            AssistantTextSanitizer.sanitize("Мне нужно знать твой бюджет.").contains("Мне нужно знать")
        )
        assertTrue(
            "a marker word inside a sentence must survive",
            AssistantTextSanitizer.sanitize("Моя мысль проста: всё работает.").contains("всё работает")
        )
        assertFalse(
            "a planning line that does not name a tool must survive",
            AssistantTextSanitizer.sanitize("I need to understand the request first.").isBlank()
        )
    }

    @Test
    fun realtimeDropsAnOpenReasoningBlockInsteadOfShowingIt() {
        // While a long chain of thought is still streaming its closing tag has not arrived. The
        // streaming pipeline must hide it from the first chunk — not show it and remove it later.
        assertEquals(
            "Готово.",
            AssistantTextSanitizer.sanitizeRealtime("Готово.\n<thinking>пользователь просит свет, значит")
        )
        assertEquals(
            "",
            AssistantTextSanitizer.sanitizeRealtime("<thought>step 1: проверить батарею")
        )
        // A complete block is still removed, and the surrounding answer is kept.
        assertEquals(
            "Фонарик включён.",
            AssistantTextSanitizer.sanitizeRealtime(
                "<thinking>пользователь хочет свет</thinking>\nФонарик включён."
            )
        )
        // Ordinary text without any reasoning markers is untouched.
        assertEquals(
            "Просто ответ.",
            AssistantTextSanitizer.sanitizeRealtime("Просто ответ.")
        )
    }

    @Test
    fun realtimeHandlesThoughtTagsAndKeepsTextBeforeTheBlock() {
        assertEquals(
            "Начало",
            AssistantTextSanitizer.sanitizeRealtime("Начало <thought>внутреннее")
        )
        assertEquals(
            "Ответ.\n\nпродолжение",
            AssistantTextSanitizer.sanitizeRealtime(
                "Ответ.\n<thought>рассуждаю</thought>\nпродолжение"
            )
        )
    }
}
