package com.andharness.ru.data.notifications

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure chat-routing decision ([resolveNotificationChat]) and the topic helper.
 * The Android-bound [NotificationChatRouter] only reads the store and delegates here, so this
 * covers reuse / recreate / append without a `Context`.
 */
class NotificationChatRouterTest {

    private class FakeChatRepository(
        private val existing: MutableSet<Long> = mutableSetOf(),
    ) : ChatRepository {
        var lastCreatedTitle: String? = null
        var nextId: Long = 100L
        val messages = mutableListOf<Triple<Long, String, String>>()

        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(emptyList())
        override fun observeChat(chatId: Long): Flow<ChatEntity?> = flowOf(null)
        override suspend fun createChat(title: String): Long {
            lastCreatedTitle = title
            val id = nextId++
            existing += id
            return id
        }

        override suspend fun chatExists(chatId: Long): Boolean = chatId in existing
        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun addMessage(
            chatId: Long,
            role: String,
            content: String,
            toolName: String?,
        ): Long {
            messages += Triple(chatId, role, content)
            return messages.size.toLong()
        }

        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) = Unit
        override suspend fun clearStaleStreaming(): Int = 0
        override suspend fun deleteChat(chatId: Long) {
            existing -= chatId
        }

        override suspend fun renameChat(chatId: Long, title: String) = Unit
        override suspend fun updateSummary(chatId: Long, summary: String) = Unit
        override suspend fun updateContextState(chatId: Long, state: String?) = Unit
        override suspend fun pruneToLimit(keep: Int) = Unit
        override suspend fun deleteAllChats() = Unit
        override suspend fun setPinned(chatId: Long, pinned: Boolean) = Unit
        override suspend fun clearMessages(chatId: Long) = Unit
        override suspend fun mostRecentChatId(): Long? = null
    }

    @Test
    fun `reuses the stored chat when it still exists and appends the text`() = runBlocking {
        val repository = FakeChatRepository(existing = mutableSetOf(42L))

        val resolution = resolveNotificationChat(
            title = NotificationChatTopics.GENERAL_TITLE,
            text = "текст",
            storedChatId = 42L,
            chatRepository = repository,
        )

        assertEquals(42L, resolution.chatId)
        assertFalse(resolution.created)
        assertEquals(null, repository.lastCreatedTitle)
        assertEquals(listOf(Triple(42L, "assistant", "текст")), repository.messages)
    }

    @Test
    fun `creates the chat when nothing was stored yet`() = runBlocking {
        val repository = FakeChatRepository()

        val resolution = resolveNotificationChat(
            title = NotificationChatTopics.GENERAL_TITLE,
            text = "текст",
            storedChatId = null,
            chatRepository = repository,
        )

        assertTrue(resolution.created)
        assertEquals(NotificationChatTopics.GENERAL_TITLE, repository.lastCreatedTitle)
        assertEquals(resolution.chatId, repository.messages.single().first)
        assertEquals("assistant", repository.messages.single().second)
        assertEquals("текст", repository.messages.single().third)
    }

    @Test
    fun `recreates the chat when the stored id no longer exists`() = runBlocking {
        val repository = FakeChatRepository()

        val resolution = resolveNotificationChat(
            title = NotificationChatTopics.GENERAL_TITLE,
            text = "текст",
            storedChatId = 7L,
            chatRepository = repository,
        )

        assertTrue(resolution.created)
        assertNotEquals(7L, resolution.chatId)
        assertEquals(NotificationChatTopics.GENERAL_TITLE, repository.lastCreatedTitle)
        assertEquals(resolution.chatId, repository.messages.single().first)
    }

    @Test
    fun `topic helper is deterministic and distinct per base id`() {
        assertEquals(
            NotificationChatTopics.forBaseId(1012),
            NotificationChatTopics.forBaseId(1012),
        )
        assertNotEquals(
            NotificationChatTopics.forBaseId(1012),
            NotificationChatTopics.forBaseId(1013),
        )
    }
}
