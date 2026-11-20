package com.andharness.ru.domain.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAttemptGuardTest {

    @Test
    fun triggersOnIncapabilityWithoutAnyToolCall() {
        assertTrue(
            "ru «не могу»",
            NoAttemptGuard.shouldCorrect(0, "Я не могу это сделать без твоего участия.")
        )
        assertTrue(
            "ru «невозможно»",
            NoAttemptGuard.shouldCorrect(0, "Это невозможно на этой версии Android.")
        )
        assertTrue(
            "ru «нет возможности»",
            NoAttemptGuard.shouldCorrect(0, "У меня нет возможности открыть этот файл.")
        )
        assertTrue(
            "ru «к сожалению, я не»",
            NoAttemptGuard.shouldCorrect(0, "К сожалению, я не умею это делать.")
        )
        assertTrue(
            "ru «не получится»",
            NoAttemptGuard.shouldCorrect(0, "Так не получится.")
        )
        assertTrue(
            "en \"I can't\"",
            NoAttemptGuard.shouldCorrect(0, "I can't do that on this device.")
        )
        assertTrue(
            "en \"unable to\"",
            NoAttemptGuard.shouldCorrect(0, "I am unable to perform this action.")
        )
        assertTrue(
            "en \"cannot\"",
            NoAttemptGuard.shouldCorrect(0, "I cannot access that.")
        )
    }

    @Test
    fun doesNotTriggerWhenAtLeastOneToolWasCalled() {
        assertFalse(
            "one tool call + refusal",
            NoAttemptGuard.shouldCorrect(1, "Я не могу этого сделать, инструмент вернул ошибку.")
        )
        assertFalse(
            "many tool calls + refusal",
            NoAttemptGuard.shouldCorrect(5, "К сожалению, не получилось — нет возможности.")
        )
    }

    @Test
    fun doesNotTriggerOnANormalAnswer() {
        assertFalse(
            "plain answer",
            NoAttemptGuard.shouldCorrect(0, "Фонарик включён.")
        )
        assertFalse(
            "question",
            NoAttemptGuard.shouldCorrect(0, "Уточни, пожалуйста, какой файл открыть?")
        )
        assertFalse(
            "blank",
            NoAttemptGuard.shouldCorrect(0, "")
        )
    }

    @Test
    fun oneShotEnforcementIgnoresTheSecondTrigger() {
        val text = "Я не могу это сделать."

        assertTrue(
            "first trigger corrects",
            NoAttemptGuard.shouldCorrectOnce(alreadyCorrected = false, toolCallsMadeInRun = 0, finalText = text)
        )
        assertFalse(
            "second trigger ignored",
            NoAttemptGuard.shouldCorrectOnce(alreadyCorrected = true, toolCallsMadeInRun = 0, finalText = text)
        )
    }

    @Test
    fun correctiveMessageIsNonBlankAndActionable() {
        assertTrue(NoAttemptGuard.CORRECTIVE_MESSAGE.isNotBlank())
        assertTrue(
            "points at tools + honest fallback",
            NoAttemptGuard.CORRECTIVE_MESSAGE.contains("инструмент")
        )
    }
}
