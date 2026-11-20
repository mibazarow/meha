package com.andharness.ru.ui.chats

import com.andharness.ru.data.db.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ChatTranscriptVisibility]: which rows render in the transcript, including the new
 * rule that a `device_search` tool row carrying a structured payload becomes a rich preview block
 * while every other tool row stays hidden.
 */
class ChatTranscriptVisibilityTest {

    private fun message(
        id: Long,
        role: String,
        content: String = "",
        toolName: String? = null,
        payload: String? = null
    ) = MessageEntity(
        id = id,
        chatId = 1L,
        role = role,
        content = content,
        toolName = toolName,
        createdAt = id,
        payload = payload
    )

    @Test
    fun userMessageIsAlwaysVisible() {
        assertTrue(ChatTranscriptVisibility.isVisible(message(1, "user", "hi"), null))
        assertTrue(ChatTranscriptVisibility.isVisible(message(2, "user", ""), null))
    }

    @Test
    fun assistantMessageVisibleOnlyWithContent() {
        assertFalse(ChatTranscriptVisibility.isVisible(message(3, "assistant", ""), null))
        assertTrue(ChatTranscriptVisibility.isVisible(message(4, "assistant", "done"), null))
    }

    @Test
    fun deviceSearchToolRowWithPayloadIsVisible() {
        val row = message(5, "tool", "raw text", toolName = "device_search", payload = "{\"items\":[]}")
        assertTrue(ChatTranscriptVisibility.isVisible(row, null))
    }

    @Test
    fun deviceSearchToolRowWithoutPayloadIsHidden() {
        val row = message(6, "tool", "raw text", toolName = "device_search", payload = null)
        assertFalse(ChatTranscriptVisibility.isVisible(row, null))
        val blank = message(7, "tool", "raw text", toolName = "device_search", payload = "  ")
        assertFalse(ChatTranscriptVisibility.isVisible(blank, null))
    }

    @Test
    fun otherToolRowWithPayloadIsHidden() {
        val row = message(8, "tool", "out", toolName = "files_read", payload = "{\"items\":[]}")
        assertFalse(ChatTranscriptVisibility.isVisible(row, null))
    }

    @Test
    fun streamingMessageIsHiddenRegardlessOfRole() {
        val streaming = message(9, "assistant", "partial")
        assertFalse(ChatTranscriptVisibility.isVisible(streaming, streamingMessageId = 9L))
        val userStreaming = message(10, "user", "typed")
        assertFalse(ChatTranscriptVisibility.isVisible(userStreaming, streamingMessageId = 10L))
    }

    @Test
    fun visiblePreservesOrderAndFilters() {
        val messages = listOf(
            message(1, "user", "q"),
            message(2, "tool", "raw", toolName = "device_search", payload = "{}"),
            message(3, "tool", "raw", toolName = "files_read"),
            message(4, "assistant", ""),
            message(5, "assistant", "answer")
        )
        val visible = ChatTranscriptVisibility.visible(messages, streamingMessageId = null)
        assertEquals(listOf(1L, 2L, 5L), visible.map { it.id })
    }
}
