package com.andharness.ru.domain.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive truth table for the update.md bug #5 gate:
 * suppress only when the app is foreground AND the target chat is the open one.
 */
class ChatNotificationGateTest {

    @Test
    fun `suppresses when foreground and the target chat is open`() {
        assertTrue(
            ChatNotificationGate.shouldSuppress(
                appForeground = true,
                activeChatId = 7L,
                targetChatId = 7L,
            )
        )
    }

    @Test
    fun `shows when foreground but a different chat is open`() {
        assertFalse(
            ChatNotificationGate.shouldSuppress(
                appForeground = true,
                activeChatId = 8L,
                targetChatId = 7L,
            )
        )
    }

    @Test
    fun `shows when foreground and no chat is open (list, home, settings)`() {
        assertFalse(
            ChatNotificationGate.shouldSuppress(
                appForeground = true,
                activeChatId = null,
                targetChatId = 7L,
            )
        )
    }

    @Test
    fun `shows when backgrounded even if the same chat is nominally active`() {
        assertFalse(
            ChatNotificationGate.shouldSuppress(
                appForeground = false,
                activeChatId = 7L,
                targetChatId = 7L,
            )
        )
    }

    @Test
    fun `never suppresses a notification that targets no chat`() {
        assertFalse(
            ChatNotificationGate.shouldSuppress(
                appForeground = true,
                activeChatId = 7L,
                targetChatId = null,
            )
        )
        assertFalse(
            ChatNotificationGate.shouldSuppress(
                appForeground = true,
                activeChatId = null,
                targetChatId = null,
            )
        )
        assertFalse(
            ChatNotificationGate.shouldSuppress(
                appForeground = false,
                activeChatId = null,
                targetChatId = null,
            )
        )
    }

    @Test
    fun `full matrix - only the foreground matching case is suppressed`() {
        val chats = listOf<Long?>(null, 7L)
        val active = listOf<Long?>(null, 7L, 8L)
        val foreground = listOf(true, false)
        var suppressed = 0
        var shown = 0
        for (fg in foreground) {
            for (a in active) {
                for (t in chats) {
                    val result = ChatNotificationGate.shouldSuppress(fg, a, t)
                    if (result) {
                        suppressed++
                        assertTrue(fg && a == t && t != null)
                    } else {
                        shown++
                    }
                }
            }
        }
        // Exactly one combination out of the 12 is suppressed.
        org.junit.Assert.assertEquals(1, suppressed)
        org.junit.Assert.assertEquals(11, shown)
    }
}
