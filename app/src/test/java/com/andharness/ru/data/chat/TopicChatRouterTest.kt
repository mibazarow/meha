package com.andharness.ru.data.chat

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
 * JVM tests for the neutral topic→chat core ([ensureTopicChat] / [resolveTopicChat]). The
 * Android-bound [TopicChatRouter] only reads the store and delegates here, so reuse / recreate /
 * append are covered with a fake [ChatRepository], without a `Context`.
 */
class TopicChatRouterTest {

    private class FakeChatRepository(
        private val existing: MutableSet<Long> = mutableSetOf(),
    ) : ChatRepository {
        var lastCreatedTitle: String? = null
        var createChatCalls = 0
        var nextId: Long = 100L
        val messages = mutableListOf<Triple<Long, String, String>>()

        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(emptyList())
        override fun observeChat(chatId: Long): Flow<ChatEntity?> = flowOf(null)
        override suspend fun createChat(title: String): Long {
            lastCreatedTitle = title
            createChatCalls++
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
    fun `ensure reuses the stored chat and writes no message`() = runBlocking {
        val repository = FakeChatRepository(existing = mutableSetOf(42L))

        val resolution = ensureTopicChat(
            title = "Плавающий помощник",
            storedChatId = 42L,
            chatRepository = repository,
        )

        assertEquals(42L, resolution.chatId)
        assertFalse(resolution.created)
        assertEquals(null, repository.lastCreatedTitle)
        assertTrue(repository.messages.isEmpty())
    }

    @Test
    fun `ensure creates the chat when nothing was stored`() = runBlocking {
        val repository = FakeChatRepository()

        val resolution = ensureTopicChat(
            title = "Плавающий помощник",
            storedChatId = null,
            chatRepository = repository,
        )

        assertTrue(resolution.created)
        assertEquals("Плавающий помощник", repository.lastCreatedTitle)
        assertTrue(repository.messages.isEmpty())
    }

    @Test
    fun `ensure recreates the chat when the stored id no longer exists`() = runBlocking {
        val repository = FakeChatRepository()

        val resolution = ensureTopicChat(
            title = "Плавающий помощник",
            storedChatId = 7L,
            chatRepository = repository,
        )

        assertTrue(resolution.created)
        assertNotEquals(7L, resolution.chatId)
        assertEquals("Плавающий помощник", repository.lastCreatedTitle)
    }

    @Test
    fun `two overlay ensures reuse one chat id and never create twice`() = runBlocking {
        val repository = FakeChatRepository()

        val first = ensureTopicChat("Плавающий помощник", storedChatId = null, chatRepository = repository)
        val second = ensureTopicChat(
            "Плавающий помощник",
            storedChatId = first.chatId,
            chatRepository = repository,
        )

        assertEquals(first.chatId, second.chatId)
        assertFalse(second.created)
        assertEquals("Плавающий помощник", repository.lastCreatedTitle)
        assertEquals(1, repository.createChatCalls)
    }

    @Test
    fun `resolve appends the text under the assistant role by default`() = runBlocking {
        val repository = FakeChatRepository(existing = mutableSetOf(42L))

        resolveTopicChat(
            title = "Уведомления",
            text = "текст",
            storedChatId = 42L,
            chatRepository = repository,
        )

        assertEquals(listOf(Triple(42L, "assistant", "текст")), repository.messages)
    }

    @Test
    fun `resolve appends the overlay question under the user role`() = runBlocking {
        val repository = FakeChatRepository(existing = mutableSetOf(42L))

        resolveTopicChat(
            title = "Плавающий помощник",
            text = "что на экране",
            storedChatId = 42L,
            chatRepository = repository,
            role = "user",
        )

        assertEquals(listOf(Triple(42L, "user", "что на экране")), repository.messages)
    }
}
