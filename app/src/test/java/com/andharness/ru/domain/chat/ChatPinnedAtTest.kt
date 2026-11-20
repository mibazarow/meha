package com.andharness.ru.domain.chat

import com.andharness.ru.data.db.entity.ChatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM checks for the chat pin marker: a freshly built [ChatEntity] is unpinned, and the
 * pin -> `pinnedAt` mapping ([pinnedAtFor]) stores epoch millis when pinned and 0 when not.
 * No Room, no device.
 */
class ChatPinnedAtTest {

    @Test
    fun chatEntityDefaultsToUnpinned() {
        val chat = ChatEntity(title = "t", createdAt = 1, updatedAt = 2)
        assertEquals(0L, chat.pinnedAt)
    }

    @Test
    fun pinnedAtForStoresNowWhenPinnedAndZeroWhenCleared() {
        val now = 1_700_000_000_000L
        assertEquals(now, pinnedAtFor(pinned = true, now = now))
        assertTrue("pinned marker must be positive", pinnedAtFor(pinned = true, now = now) > 0L)
        assertEquals(0L, pinnedAtFor(pinned = false, now = now))
    }
}
