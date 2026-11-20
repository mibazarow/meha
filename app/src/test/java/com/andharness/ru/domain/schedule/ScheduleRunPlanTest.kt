package com.andharness.ru.domain.schedule

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug #4: the scheduled run must go into the schedule's own chat, carry the current time, and report
 * failures honestly instead of leaving the chat empty / staying silent. Pure logic, so it is tested
 * on the JVM without Android/WorkManager.
 */
class ScheduleRunPlanTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    /** 2026-09-17T17:45:00Z = 20:45 MSK, a Thursday. */
    private val now = Instant.parse("2026-09-17T17:45:00Z").toEpochMilli()
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun runMessageCarriesTheCurrentTimeAndTheTaskPrompt() {
        val message = ScheduleRunPlan.runMessage("показывай курс доллара", now, moscow, ru)
        assertTrue("must state the weekday in the user's locale", message.contains("четверг"))
        assertTrue("must state the current date", message.contains("17.09.2026"))
        assertTrue("must state the current time", message.contains("20:45"))
        assertTrue("must state the timezone", message.contains("Europe/Moscow"))
        assertTrue("must carry the task prompt itself", message.contains("показывай курс доллара"))
    }

    @Test
    fun runMessageTrimsTheStoredPrompt() {
        val message = ScheduleRunPlan.runMessage("  курс доллара \n", now, moscow, ru)
        assertTrue("the prompt must be carried verbatim (trimmed)", message.endsWith("курс доллара"))
    }

    @Test
    fun targetChatPrefersTheSchedulesOriginatingChat() {
        assertEquals(5L, ScheduleRunPlan.targetChatId(5L, 9L))
        assertEquals(9L, ScheduleRunPlan.targetChatId(null, 9L))
        assertEquals(9L, ScheduleRunPlan.targetChatId(0L, 9L))
        assertNull("no chat at all must be reported as null", ScheduleRunPlan.targetChatId(null, null))
    }

    @Test
    fun failureTextIsHonestAndNeverEmpty() {
        assertTrue(ScheduleRunPlan.failureMessage(null).contains("Не получилось"))
        assertTrue(ScheduleRunPlan.failureMessage("   ").contains("неизвестная ошибка"))
        assertTrue(ScheduleRunPlan.failureMessage("нет сети").contains("нет сети"))
    }

    @Test
    fun notificationTextDistinguishesSuccessFromFailure() {
        assertEquals("Задача по расписанию выполнена", ScheduleRunPlan.notificationText(null))
        val failed = ScheduleRunPlan.notificationText("нет сети")
        assertTrue(failed.contains("не выполнена"))
        assertTrue(failed.contains("нет сети"))
    }

    @Test
    fun reminderNotificationEchoesThePromptAndReadsAsAReminder() {
        val text = ScheduleRunPlan.reminderNotificationText("  купить хлеб \n", null)
        assertTrue("must read as a reminder", text.startsWith("Напоминание:"))
        assertTrue("must echo the stored prompt", text.contains("купить хлеб"))
        assertTrue("must not be worded as a scheduled run", !text.contains("Задача по расписанию"))
    }

    @Test
    fun reminderNotificationTruncatesAVeryLongPrompt() {
        val text = ScheduleRunPlan.reminderNotificationText("а".repeat(500), null)
        assertEquals("the echoed prompt is capped at 120 chars", 120, text.removePrefix("Напоминание: ").length)
    }

    @Test
    fun reminderNotificationWithoutAPromptStillSaysItFired() {
        assertEquals("Напоминание сработало", ScheduleRunPlan.reminderNotificationText("   ", null))
    }

    @Test
    fun reminderNotificationReportsFailureHonestly() {
        val failed = ScheduleRunPlan.reminderNotificationText("купить хлеб", "нет сети")
        assertTrue(failed.contains("Напоминание не выполнено"))
        assertTrue(failed.contains("нет сети"))
    }

    @Test
    fun reminderFailureMessageIsHonestAndNeverEmpty() {
        assertTrue(ScheduleRunPlan.reminderFailureMessage(null).contains("напоминание"))
        assertTrue(ScheduleRunPlan.reminderFailureMessage("   ").contains("неизвестная ошибка"))
        assertTrue(ScheduleRunPlan.reminderFailureMessage("нет сети").contains("нет сети"))
    }
}
