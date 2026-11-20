package com.andharness.ru.data.tools.misc


/** One row of `chat_list`: what the list shows about a chat, already reduced to what fits. */
internal data class ChatSummaryRow(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val lastPreview: String?,
)
