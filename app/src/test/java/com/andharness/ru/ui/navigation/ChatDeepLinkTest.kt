package com.andharness.ru.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM tests for [ChatDeepLink], the value that carries a notification deep-link target from
 * `MainActivity` into `AppNavHost`.
 *
 * [nonce] is what makes the consumption idempotent yet re-triggerable: `AppNavHost` watches
 * `deepLink?.nonce` in a `LaunchedEffect` and skips when the nonce is unchanged (a recomposition /
 * rotation must not navigate twice), while a genuine re-tap builds a NEW link with a fresh nonce so
 * the StateFlow emits it and navigates again.
 */
class ChatDeepLinkTest {

    @Test
    fun `a link carries its chat id and optional message id`() {
        val link = ChatDeepLink(chatId = 7L, messageId = 99L)
        assertEquals(7L, link.chatId)
        assertEquals(99L, link.messageId)
    }

    @Test
    fun `a link without a message id has a null message id`() {
        val link = ChatDeepLink(chatId = 7L)
        assertEquals(7L, link.chatId)
        assertEquals(null, link.messageId)
    }

    @Test
    fun `two independently built links for the same chat carry different nonces`() {
        assertNotEquals(
            ChatDeepLink(chatId = 7L).nonce,
            ChatDeepLink(chatId = 7L).nonce,
        )
    }
}
