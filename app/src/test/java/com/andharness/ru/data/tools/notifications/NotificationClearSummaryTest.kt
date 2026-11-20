package com.andharness.ru.data.tools.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClearSummaryTest {

    @Test
    fun emptyActiveSet() {
        assertEquals("Активных уведомлений нет.", NotificationClearSummary.format(0, 0))
    }

    @Test
    fun allCleared() {
        assertEquals("Снято уведомлений: 5.", NotificationClearSummary.format(5, 0))
    }

    @Test
    fun partialRemainderIsReportedHonestly() {
        val text = NotificationClearSummary.format(5, 2)
        assertTrue(text, text.contains("3"))
        assertTrue(text, text.contains("5"))
        assertTrue(text, text.contains("2"))
    }
}
