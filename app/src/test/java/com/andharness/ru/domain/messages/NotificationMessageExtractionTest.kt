package com.andharness.ru.domain.messages

import com.andharness.ru.domain.messages.NotificationMessageExtraction.RawMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [NotificationMessageExtraction]: the MessagingStyle → list-of-messages mapping,
 * group detection, single-message fallback and per-package source kind. No Android runtime.
 */
class NotificationMessageExtractionTest {

    private fun raw(text: String, at: Long, sender: String? = null) = RawMessage(text, at, sender)

    // ---- multi-message mapping ----

    @Test
    fun everyRawMessageBecomesOneRowInOrderWithFullText() {
        val result = NotificationMessageExtraction.extract(
            raw = listOf(
                raw("первое сообщение", 100, "Алиса"),
                raw("второе сообщение, подлиннее", 200, "Алиса"),
                raw("третье", 300, "Боб"),
            ),
            conversationTitle = "Команда",
            fallbackSender = "fallback",
            fallbackText = "fallback body",
            fallbackAt = 999,
        )

        assertEquals(3, result.messages.size)
        assertEquals(listOf("первое сообщение", "второе сообщение, подлиннее", "третье"),
            result.messages.map { it.text })
        assertEquals(listOf(100L, 200L, 300L), result.messages.map { it.at })
        assertEquals(listOf("Алиса", "Алиса", "Боб"), result.messages.map { it.sender })
    }

    @Test
    fun blankRawMessagesAreDropped() {
        val result = NotificationMessageExtraction.extract(
            raw = listOf(raw("  ", 1, null), raw("ok", 2, null)),
            conversationTitle = null,
            fallbackSender = null,
            fallbackText = null,
            fallbackAt = 0,
        )

        assertEquals(listOf("ok"), result.messages.map { it.text })
    }

    // ---- group detection ----

    @Test
    fun conversationTitleMarksAGroup() {
        val result = NotificationMessageExtraction.extract(
            raw = listOf(raw("привет", 1, "Алиса")),
            conversationTitle = "Друзья",
            fallbackSender = null,
            fallbackText = null,
            fallbackAt = 0,
        )

        assertTrue(result.isGroup)
        assertEquals("Друзья", result.conversationKey)
    }

    @Test
    fun twoDistinctParticipantsMarkAGroupEvenWithoutTitle() {
        val result = NotificationMessageExtraction.extract(
            raw = listOf(raw("a", 1, "Алиса"), raw("b", 2, "Боб")),
            conversationTitle = null,
            fallbackSender = null,
            fallbackText = null,
            fallbackAt = 0,
        )

        assertTrue(result.isGroup)
        assertEquals("Алиса", result.conversationKey)
    }

    @Test
    fun aSingleParticipantWithoutTitleIsNotAGroup() {
        val result = NotificationMessageExtraction.extract(
            raw = listOf(raw("a", 1, "Алиса"), raw("b", 2, "Алиса")),
            conversationTitle = null,
            fallbackSender = null,
            fallbackText = null,
            fallbackAt = 0,
        )

        assertFalse(result.isGroup)
        assertEquals("Алиса", result.conversationKey)
    }

    // ---- fallback single message ----

    @Test
    fun noMessagingStyleFallsBackToTitleAndText() {
        val result = NotificationMessageExtraction.extract(
            raw = emptyList(),
            conversationTitle = null,
            fallbackSender = "+79990001122",
            fallbackText = "текст уведомления",
            fallbackAt = 555,
        )

        assertEquals(1, result.messages.size)
        assertEquals("текст уведомления", result.messages[0].text)
        assertEquals(555L, result.messages[0].at)
        assertEquals("+79990001122", result.messages[0].sender)
        assertEquals("+79990001122", result.conversationKey)
        assertFalse(result.isGroup)
    }

    @Test
    fun blankFallbackTextYieldsNoMessages() {
        val result = NotificationMessageExtraction.extract(
            raw = emptyList(),
            conversationTitle = null,
            fallbackSender = "someone",
            fallbackText = "   ",
            fallbackAt = 7,
        )

        assertTrue(result.messages.isEmpty())
        assertNull(result.conversationKey)
        assertFalse(result.isGroup)
    }

    @Test
    fun titleWinsOverSenderForTheConversationKey() {
        val result = NotificationMessageExtraction.extract(
            raw = emptyList(),
            conversationTitle = "Группа",
            fallbackSender = "sender",
            fallbackText = "hi",
            fallbackAt = 1,
        )

        assertEquals("Группа", result.conversationKey)
        assertTrue(result.isGroup)
    }

    // ---- source kind ----

    @Test
    fun smsMailAndMessengerPackagesMapToTheirSourceType() {
        assertEquals(
            StoredMessageSource.SMS,
            NotificationMessageExtraction.sourceTypeFor("com.google.android.apps.messaging")
        )
        assertEquals(
            StoredMessageSource.EMAIL,
            NotificationMessageExtraction.sourceTypeFor("com.google.android.gm")
        )
        assertEquals(
            StoredMessageSource.MESSENGER,
            NotificationMessageExtraction.sourceTypeFor("org.telegram.messenger")
        )
        assertEquals(
            StoredMessageSource.MESSENGER,
            NotificationMessageExtraction.sourceTypeFor(null)
        )
    }
}
