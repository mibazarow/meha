package com.andharness.ru.inbound

import com.andharness.ru.domain.chat.ChatAttachment

/**
 * What an inbound entry point (update.md idea #10) prepared for a freshly created chat.
 *
 * [text] is the exact composer / user-message text: for a text share it already carries the
 * default «объясни и перескажи» instruction (see [InboundIntentHandler]), for a process-text or a
 * file share it is the raw selected / shared text.
 *
 * [attachments] are already copied into app-private storage, so they survive the temporary URI
 * grant that the sharing app gave us.
 */
data class InboundChatSeed(
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val autoRun: Boolean = false,
    /** Honest status shown under the composer when a shared file had to be dropped. */
    val notice: String? = null
)
