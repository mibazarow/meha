package com.andharness.ru.ui.chats

import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.DeviceSearchPayload

/**
 * Which persisted rows actually appear in the chat transcript. Pure (no Android, no Compose) so the
 * rules are unit-tested on the JVM and stay in one place.
 *
 * - `user` rows always show.
 * - `assistant` rows show only once they carry real text (redesign §2 phase 6: no empty bubble).
 * - `tool` rows are normally hidden (§17: the single status line reports tool activity) — EXCEPT a
 *   `device_search` row that carries a structured [MessageEntity.payload], which is rendered as a
 *   rich preview block (photo thumbnails / document icons / fallback links).
 * - The message the current run is writing (`streamingMessageId`) is hidden until the turn finishes.
 */
object ChatTranscriptVisibility {

    /** True when [message] should be rendered in the transcript (given the in-flight id). */
    fun isVisible(message: MessageEntity, streamingMessageId: Long?): Boolean {
        if (message.id == streamingMessageId) return false
        return when (message.role) {
            ROLE_USER -> true
            ROLE_ASSISTANT -> message.content.isNotBlank()
            ROLE_TOOL -> message.toolName == DeviceSearchPayload.TOOL_NAME &&
                !message.payload.isNullOrBlank()
            else -> false
        }
    }

    /** The visible rows of [messages], preserving their order. */
    fun visible(messages: List<MessageEntity>, streamingMessageId: Long?): List<MessageEntity> =
        messages.filter { isVisible(it, streamingMessageId) }

    internal const val ROLE_USER = "user"
    internal const val ROLE_ASSISTANT = "assistant"
    internal const val ROLE_TOOL = "tool"
}
