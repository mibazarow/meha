package com.andharness.ru.domain.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTriageTest {

    private fun event(
        text: String,
        title: String = "",
        sender: String? = "Иван",
        group: Boolean = false,
        missedCall: Boolean = false,
        repeat: Int = 1,
    ) = NotificationEvent(
        app = "org.telegram.messenger",
        sender = sender,
        title = title,
        text = text,
        at = 1_700_000_000_000L,
        isGroupOrBulk = group,
        isMissedCall = missedCall,
        repeatCount = repeat,
    )

    @Test
    fun urgentRussianKeywordIsHigh() {
        val result = NotificationTriage.triage(event("Срочно оплатите счёт до 20:00"))
        assertEquals(TriageImportance.HIGH, result.importance)
        assertEquals(TriageCategory.MONEY, result.category)
        assertTrue(result.reasons.isNotEmpty())
        assertFalse(result.secret)
    }

    @Test
    fun urgentEnglishKeywordIsHigh() {
        val result = NotificationTriage.triage(event("Urgent: please call me back"))
        assertEquals(TriageImportance.HIGH, result.importance)
    }

    @Test
    fun groupOrBulkIsLowEvenFromKnownSender() {
        val result = NotificationTriage.triage(event("Привет всем, кто идёт сегодня?", group = true))
        assertEquals(TriageImportance.LOW, result.importance)
        assertEquals(TriageCategory.BULK, result.category)
    }

    @Test
    fun otpIsLowAndSecret() {
        val result = NotificationTriage.triage(
            event("Ваш код подтверждения: 4821. Никому не сообщайте.", title = "Bank")
        )
        assertEquals(TriageImportance.LOW, result.importance)
        assertEquals(TriageCategory.OTP, result.category)
        assertTrue("OTP must be flagged secret", result.secret)
    }

    @Test
    fun otpIsNeverMemorizedInAnyMode() {
        val otp = NotificationTriage.triage(event("Код входа 123456"))
        assertFalse(MemoryPolicy.shouldMemorize(otp, MemorizeMode.ALL))
        assertFalse(MemoryPolicy.shouldMemorize(otp, MemorizeMode.HIGH_ONLY))
        assertFalse(MemoryPolicy.shouldMemorize(otp, MemorizeMode.OFF))
    }

    @Test
    fun higherModesGateOnImportance() {
        val high = NotificationTriage.triage(event("Срочно перезвони"))
        val normal = NotificationTriage.triage(event("Привет, как дела?"))
        val low = NotificationTriage.triage(event("Реклама", sender = null, group = true))

        assertTrue(MemoryPolicy.shouldMemorize(high, MemorizeMode.HIGH_ONLY))
        assertFalse(MemoryPolicy.shouldMemorize(normal, MemorizeMode.HIGH_ONLY))
        assertTrue(MemoryPolicy.shouldMemorize(normal, MemorizeMode.ALL))
        assertFalse(MemoryPolicy.shouldMemorize(low, MemorizeMode.ALL))
        assertFalse(MemoryPolicy.shouldMemorize(high, MemorizeMode.OFF))
    }

    @Test
    fun missedCallIsHigh() {
        val result = NotificationTriage.triage(event("Пропущенный вызов", missedCall = true))
        assertEquals(TriageImportance.HIGH, result.importance)
        assertEquals(TriageCategory.CALL, result.category)
    }

    @Test
    fun repeatedSenderIsHigh() {
        val result = NotificationTriage.triage(event("Ну ты где?", repeat = NotificationTriage.REPEAT_THRESHOLD))
        assertEquals(TriageImportance.HIGH, result.importance)
    }

    @Test
    fun directKnownContactIsNormal() {
        val result = NotificationTriage.triage(event("Как дела?").copy(senderKnown = true))
        assertEquals(TriageImportance.NORMAL, result.importance)
        assertEquals(TriageCategory.PERSONAL, result.category)
    }

    @Test
    fun summaryIsBoundedAndClean() {
        val long = "🔥 Привет!!! https://example.com/very/long/link " + "текст ".repeat(100)
        val summary = NotificationTriage.summarize("Иван", "", long)
        assertTrue("summary must be bounded: ${summary.length}", summary.length <= NotificationTriage.MAX_SUMMARY)
        assertFalse("URLs must be stripped", summary.contains("http"))
        assertFalse("emoji must be stripped", summary.contains("🔥"))
        assertFalse("whitespace must be collapsed", summary.contains("  "))
        assertTrue(summary.startsWith("Иван:"))
    }

    @Test
    fun summaryFallsBackToTitleWhenTextIsBlank() {
        assertEquals("Тема", NotificationTriage.summarize(null, "Тема", ""))
    }

    @Test
    fun dedupeSkipsOnlyInsideWindow() {
        val window = 30L * 60L * 1000L
        val dedup = RecentNoteDedup(window)
        val t0 = 1_700_000_000_000L
        assertFalse(dedup.isDuplicate("org.telegram.messenger", "Срочно позвони", t0))
        dedup.remember("org.telegram.messenger", "Срочно позвони", t0)

        assertTrue(dedup.isDuplicate("org.telegram.messenger", "Срочно позвони", t0 + 10 * 60 * 1000L))
        assertFalse(dedup.isDuplicate("org.telegram.messenger", "Срочно позвони", t0 + 40 * 60 * 1000L))
        assertFalse(dedup.isDuplicate("com.whatsapp", "Срочно позвони", t0 + 60 * 1000L))
        assertFalse(dedup.isDuplicate("org.telegram.messenger", "Другой текст", t0 + 60 * 1000L))
    }

    @Test
    fun captureLogIsBoundedNewestFirst() {
        val log = NotificationCaptureLog()
        repeat(NotificationCaptureLog.MAX_ENTRIES + 5) { index ->
            log.add(
                CapturedNotification(
                    app = "app",
                    sender = "s",
                    summary = "msg $index",
                    importance = TriageImportance.NORMAL,
                    category = TriageCategory.PERSONAL,
                    reasons = emptyList(),
                    at = index.toLong(),
                )
            )
        }
        assertEquals(NotificationCaptureLog.MAX_ENTRIES, log.size())
        assertEquals("msg ${NotificationCaptureLog.MAX_ENTRIES + 4}", log.recent().first().summary)
    }

    @Test
    fun digestGroupsByAppAndMarksImportant() {
        val entries = listOf(
            CapturedNotification("org.telegram.messenger", "Иван", "срочно позвони", TriageImportance.HIGH, TriageCategory.PERSONAL, emptyList(), 0L),
            CapturedNotification("org.telegram.messenger", "Чат", "привет", TriageImportance.NORMAL, TriageCategory.PERSONAL, emptyList(), 0L),
            CapturedNotification("com.whatsapp", null, "реклама", TriageImportance.LOW, TriageCategory.BULK, emptyList(), 0L),
        )
        val digest = NotificationDigest.build(entries)
        assertTrue(digest.contains("важных: 1"))
        assertTrue(digest.contains("[org.telegram.messenger]"))
        assertTrue(digest.contains("[com.whatsapp]"))
        assertTrue(digest.contains("ВАЖНО"))
    }

    @Test
    fun digestHasHonestEmptyState() {
        val digest = NotificationDigest.build(emptyList())
        assertTrue(digest.contains("нет"))
    }
}
