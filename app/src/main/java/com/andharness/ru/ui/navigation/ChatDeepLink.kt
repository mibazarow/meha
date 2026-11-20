package com.andharness.ru.ui.navigation

/**
 * A notification deep-link target (SPEC §20): open [chatId], optionally focusing [messageId].
 *
 * [nonce] makes every deep-link a distinct value so re-tapping the same notification re-triggers
 * navigation (StateFlow skips equal values).
 */
data class ChatDeepLink(
    val chatId: Long,
    val messageId: Long? = null,
    val nonce: Long = System.nanoTime()
)
