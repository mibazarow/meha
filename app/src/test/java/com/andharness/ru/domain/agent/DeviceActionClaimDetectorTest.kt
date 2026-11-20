package com.andharness.ru.domain.agent

import com.andharness.ru.domain.agent.DeviceActionClaimDetector.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionClaimDetectorTest {

    private val someSuccessfulTool = listOf(ToolCall("flashlight_on", true))
    private val someFailedTool = listOf(ToolCall("flashlight_on", false))

    @Test
    fun claimsCompletedDeviceActionRecognisesRussianCompletionVerbs() {
        assertTrue("opened", DeviceActionClaimDetector.claimsCompletedDeviceAction("Открыл YouTube."))
        assertTrue("turned on", DeviceActionClaimDetector.claimsCompletedDeviceAction("Включил фонарик."))
        assertTrue("turned off", DeviceActionClaimDetector.claimsCompletedDeviceAction("Выключил будильник."))
        assertTrue("sent", DeviceActionClaimDetector.claimsCompletedDeviceAction("Я отправил сообщение маме."))
        assertTrue("created", DeviceActionClaimDetector.claimsCompletedDeviceAction("Создал заметку «список»."))
        assertTrue("downloaded", DeviceActionClaimDetector.claimsCompletedDeviceAction("Скачал файл на телефон."))
        assertTrue("case insensitive", DeviceActionClaimDetector.claimsCompletedDeviceAction("ОТКРЫЛ ПРИЛОЖЕНИЕ"))
    }

    @Test
    fun claimsCompletedDeviceActionRecognisesEnglishCompletionVerbs() {
        assertTrue("opened + sent", DeviceActionClaimDetector.claimsCompletedDeviceAction("I opened WhatsApp and sent the message."))
        assertTrue("created", DeviceActionClaimDetector.claimsCompletedDeviceAction("Created a note."))
        assertTrue("installed", DeviceActionClaimDetector.claimsCompletedDeviceAction("Installed the app."))
    }

    @Test
    fun claimWithNoToolIsFlagged() {
        assertTrue(
            "RU claim, zero tools",
            DeviceActionClaimDetector.shouldCorrect("Открыл YouTube.", emptyList())
        )
        assertTrue(
            "EN claim, zero tools",
            DeviceActionClaimDetector.shouldCorrect("I opened WhatsApp and sent the message.", emptyList())
        )
    }

    @Test
    fun claimWithSuccessfulToolIsNotFlagged() {
        assertFalse(
            "a successful tool call supports the action",
            DeviceActionClaimDetector.shouldCorrect("Включил фонарик.", someSuccessfulTool)
        )
    }

    @Test
    fun claimWithFailedToolIsNotFlagged() {
        // Per the design: any tool invocation at all defers to FalseSuccessGuard; never double-flag.
        assertFalse(
            "a failed tool call is FalseSuccessGuard's business, not ours",
            DeviceActionClaimDetector.shouldCorrect("Включил фонарик.", someFailedTool)
        )
    }

    @Test
    fun incapabilityIsNotFlagged() {
        assertFalse(
            "«не смог» is not a success claim",
            DeviceActionClaimDetector.shouldCorrect("Не смог открыть приложение.", emptyList())
        )
        assertFalse(
            "negated verb is not a success claim",
            DeviceActionClaimDetector.shouldCorrect("Не открыл приложение.", emptyList())
        )
        assertFalse(
            "English negation",
            DeviceActionClaimDetector.shouldCorrect("I couldn't open the file.", emptyList())
        )
    }

    @Test
    fun pureExplanationIsNotFlagged() {
        assertFalse(
            "knowledge answer without device verbs",
            DeviceActionClaimDetector.shouldCorrect(
                "Столица Франции — Париж, население около 2,1 млн человек.",
                emptyList()
            )
        )
        assertFalse(
            "explanation with future/plan tone",
            DeviceActionClaimDetector.shouldCorrect(
                "Я открою приложение позже, когда будет сеть.",
                emptyList()
            )
        )
        assertFalse(
            "planning phrase",
            DeviceActionClaimDetector.shouldCorrect(
                "Планирую удалить эти файлы завтра.",
                emptyList()
            )
        )
    }

    @Test
    fun questionIsNotFlagged() {
        assertFalse(
            "a question is not a completion claim",
            DeviceActionClaimDetector.shouldCorrect("Открыл?", emptyList())
        )
    }

    @Test
    fun emptyTextIsNotFlagged() {
        assertFalse("blank", DeviceActionClaimDetector.shouldCorrect("", emptyList()))
        assertFalse("whitespace", DeviceActionClaimDetector.shouldCorrect("   ", emptyList()))
    }
}
