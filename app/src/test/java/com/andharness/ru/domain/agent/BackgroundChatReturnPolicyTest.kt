package com.andharness.ru.domain.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the «вернуться в исходный чат после действия в другом приложении» decision.
 *
 * The action must happen ONLY for a user-initiated (INTERACTIVE) turn that finished while Меха was
 * NOT in the foreground and that has an originating chat id. Every other combination — a
 * background/objective/automation run, an app already in the foreground, or no chat id — must leave
 * the screen alone.
 */
class BackgroundChatReturnPolicyTest {

    @Test
    fun `returns for a user-initiated run that finished while the app was away`() {
        assertTrue(
            BackgroundChatReturnPolicy.shouldReturnToOriginatingChat(
                userInitiated = true,
                appInForeground = false,
                chatId = 7L,
            )
        )
    }

    @Test
    fun `does not return for a background objective or automation run`() {
        assertFalse(
            BackgroundChatReturnPolicy.shouldReturnToOriginatingChat(
                userInitiated = false,
                appInForeground = false,
                chatId = 7L,
            )
        )
    }

    @Test
    fun `does not return when the app is already in the foreground`() {
        assertFalse(
            BackgroundChatReturnPolicy.shouldReturnToOriginatingChat(
                userInitiated = true,
                appInForeground = true,
                chatId = 7L,
            )
        )
    }

    @Test
    fun `does not return without an originating chat id`() {
        assertFalse(
            BackgroundChatReturnPolicy.shouldReturnToOriginatingChat(
                userInitiated = true,
                appInForeground = false,
                chatId = null,
            )
        )
    }

    @Test
    fun `background run with no chat id is a no-op`() {
        assertFalse(
            BackgroundChatReturnPolicy.shouldReturnToOriginatingChat(
                userInitiated = false,
                appInForeground = false,
                chatId = null,
            )
        )
    }
}
