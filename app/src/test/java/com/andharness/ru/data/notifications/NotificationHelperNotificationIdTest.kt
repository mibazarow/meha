package com.andharness.ru.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure per-chat notification-id derivation ([NotificationHelper.notificationIdFor]).
 *
 * `NotificationManager.notify` REPLACES a live notification with the same id, so a shared/fixed id
 * would make the last poster win and hide the others. Chat-targeted notifications therefore get an
 * id folded into the reserved 600_000–699_999 range, while a chat-less notification keeps its fixed
 * base id untouched (digest, model download, …).
 * https://developer.android.com/develop/ui/views/notifications/build-notification
 */
class NotificationHelperNotificationIdTest {

    private val base = NotificationHelper.NOTIFICATION_ID_DONE

    @Test
    fun `a null chat keeps the caller base id exactly`() {
        assertEquals(base, NotificationHelper.notificationIdFor(null, null, base))
        assertEquals(base, NotificationHelper.notificationIdFor(null, 42L, base))
    }

    @Test
    fun `different chats get different ids`() {
        assertNotEquals(
            NotificationHelper.notificationIdFor(7L, null, base),
            NotificationHelper.notificationIdFor(8L, null, base),
        )
    }

    @Test
    fun `different messages in the same chat get different ids`() {
        assertNotEquals(
            NotificationHelper.notificationIdFor(7L, null, base),
            NotificationHelper.notificationIdFor(7L, 42L, base),
        )
    }

    @Test
    fun `different base ids in the same chat do not overwrite each other`() {
        assertNotEquals(
            NotificationHelper.notificationIdFor(7L, null, NotificationHelper.NOTIFICATION_ID_DONE),
            NotificationHelper.notificationIdFor(7L, null, NotificationHelper.NOTIFICATION_ID_MESSAGE_BASE),
        )
    }

    @Test
    fun `the id is deterministic`() {
        assertEquals(
            NotificationHelper.notificationIdFor(7L, 42L, base),
            NotificationHelper.notificationIdFor(7L, 42L, base),
        )
    }

    @Test
    fun `a chat id stays inside the reserved chat range`() {
        val ids = listOf(
            NotificationHelper.notificationIdFor(1L, null, base),
            NotificationHelper.notificationIdFor(7L, 42L, base),
            NotificationHelper.notificationIdFor(Long.MAX_VALUE, Long.MIN_VALUE, base),
            NotificationHelper.notificationIdFor(-5L, null, base),
        )
        ids.forEach { id ->
            assertTrue(
                "id $id must be inside the reserved chat range",
                id >= NotificationHelper.NOTIFICATION_ID_CHAT_BASE &&
                    id < NotificationHelper.NOTIFICATION_ID_CHAT_BASE + NotificationHelper.NOTIFICATION_ID_ITEM_MODULUS,
            )
        }
    }

    @Test
    fun `the reserved range cannot collide with the fixed or derived ranges`() {
        val chatRangeStart = NotificationHelper.NOTIFICATION_ID_CHAT_BASE
        val chatRangeEnd = chatRangeStart + NotificationHelper.NOTIFICATION_ID_ITEM_MODULUS - 1
        // Fixed ids 1001–1017 (incl. the done id) live far below.
        assertTrue(chatRangeStart > NotificationHelper.NOTIFICATION_ID_SPEECH_MODEL_DOWNLOAD)
        // The other derived ranges end below the chat range (message/automation/job/reminder).
        assertTrue(chatRangeStart > NotificationHelper.NOTIFICATION_ID_REMINDER_BASE + NotificationHelper.NOTIFICATION_ID_ITEM_MODULUS - 1)
        // Ranges owned elsewhere: ExactAlarmReceiver 3000+, NumberChatManager 8000+, both ≤ ~108k.
        assertTrue(chatRangeStart > 108_000)
        assertEquals(699_999, chatRangeEnd)
    }
}
