package com.andharness.ru.data.chat

import com.andharness.ru.data.db.dao.ChatDao
import com.andharness.ru.data.db.dao.ChatMessageVectorDao
import com.andharness.ru.data.db.dao.MessageDao
import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.ChatMessageVectorEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatSearchFusion
import com.andharness.ru.domain.messages.MessageQueryEmbedder
import com.andharness.ru.domain.retrieval.VectorCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of [ChatSearchServiceImpl] against hand-written fakes of the three Room DAOs and a
 * fake [MessageQueryEmbedder]: the honest lexical-only fallback when the embedder is unavailable,
 * the semantic fusion when it is available, title matching, blank-query handling and the in-chat
 * scope.
 */
class ChatSearchServiceImplTest {

    private val noEmbedder = MessageQueryEmbedder { null }

    private fun message(id: Long, chatId: Long, text: String, at: Long = id) =
        MessageEntity(
            id = id,
            chatId = chatId,
            role = "user",
            content = text,
            toolName = null,
            createdAt = at,
            isStreaming = false,
        )

    private fun vector(messageId: Long, values: FloatArray) = ChatMessageVectorEntity(
        messageId = messageId,
        dim = values.size,
        vector = VectorCodec.encode(VectorCodec.normalize(values)),
        model = "test",
        updatedAt = 0L,
    )

    @Test
    fun semanticUnavailableFallsBackToLexical() = runTest {
        val chatDao = FakeChatDao(listOf(chat(id = 1L, title = "Тест")))
        val messageDao = FakeMessageDao(
            visible = listOf(message(1L, 1L, "Привет, Меха!")),
        )
        val service = ChatSearchServiceImpl(chatDao, messageDao, FakeVectorDao(), noEmbedder)

        val results = service.searchChats("привет", limit = 10)

        val result = results.single()
        assertEquals(1L, result.chatId)
        assertFalse(result.matchedByTitle)
        assertTrue(result.snippet.contains("Привет"))
        assertEquals(ChatSearchFusion.LEXICAL_SCORE, result.score, 1e-9)
    }

    @Test
    fun semanticHitIsFusedWhenEmbedderAvailable() = runTest {
        val chatDao = FakeChatDao(listOf(chat(id = 2L, title = "План")))
        val messageDao = FakeMessageDao(
            visible = emptyList(), // nothing matches lexically
            messagesByIds = listOf(message(7L, 2L, "Встречаемся завтра в полдень")),
        )
        val vectorDao = FakeVectorDao(listOf(vector(7L, floatArrayOf(1f, 0f))))
        val embedder = MessageQueryEmbedder { floatArrayOf(1f, 0f) }
        val service = ChatSearchServiceImpl(chatDao, messageDao, vectorDao, embedder)

        val results = service.searchChats("когда встреча", limit = 10)

        val result = results.single()
        assertEquals(2L, result.chatId)
        assertEquals("План", result.title)
        assertEquals(1.0, result.score, 1e-6)
        assertTrue(result.snippet.contains("Встречаемся"))
    }

    @Test
    fun titleMatchIsIncludedAndMaxScoresWithMessageHits() = runTest {
        val chatDao = FakeChatDao(listOf(chat(id = 3L, title = "Проект Меха")))
        val service = ChatSearchServiceImpl(
            chatDao,
            FakeMessageDao(),
            FakeVectorDao(),
            noEmbedder,
        )

        val results = service.searchChats("меха", limit = 10)

        val result = results.single()
        assertTrue(result.matchedByTitle)
        assertEquals(1.0, result.score, 1e-9)
    }

    @Test
    fun blankQueryAndNonPositiveLimitYieldNothing() = runTest {
        val service = ChatSearchServiceImpl(FakeChatDao(), FakeMessageDao(), FakeVectorDao(), noEmbedder)
        assertTrue(service.searchChats("   ", limit = 10).isEmpty())
        assertTrue(service.searchChats("меха", limit = 0).isEmpty())
        assertTrue(service.searchInChat(1L, "  ", limit = 10).isEmpty())
        assertTrue(service.searchInChat(0L, "меха", limit = 10).isEmpty())
    }

    @Test
    fun searchInChatIsScopedToTheChat() = runTest {
        val messageDao = FakeMessageDao(
            visibleInChat = listOf(
                message(1L, 5L, "первое совпадение"),
                message(2L, 5L, "второе совпадение", at = 2L),
            ),
        )
        val service = ChatSearchServiceImpl(FakeChatDao(), messageDao, FakeVectorDao(), noEmbedder)

        val matches = service.searchInChat(5L, "совпадение", limit = 10)

        assertEquals(listOf(2L, 1L), matches.map { it.messageId })
        assertEquals(2L, matches.first().timestamp)
        assertTrue(matches.all { it.snippet.contains("совпадение") })
    }

    private fun chat(id: Long, title: String) =
        ChatEntity(id = id, title = title, createdAt = 0L, updatedAt = id)

    private class FakeChatDao(
        private val recentChats: List<ChatEntity> = emptyList(),
        private val chatsByIds: List<ChatEntity> = emptyList(),
    ) : ChatDao {
        override suspend fun insert(entity: ChatEntity): Long = TODO()
        override fun getById(id: Long): ChatEntity? = TODO()
        override suspend fun countById(id: Long): Int = TODO()
        override fun observeAll(): Flow<List<ChatEntity>> = TODO()
        override suspend fun updateTitle(id: Long, title: String, updatedAt: Long) = TODO()
        override suspend fun updatePinnedAt(id: Long, pinnedAt: Long) = TODO()
        override suspend fun updateUpdatedAt(id: Long, updatedAt: Long) = TODO()
        override suspend fun deleteById(id: Long) = TODO()
        override suspend fun deleteAll() = TODO()
        override fun observeById(id: Long): Flow<ChatEntity?> = TODO()
        override suspend fun updateSummary(id: Long, summary: String) = TODO()
        override suspend fun updateContextState(id: Long, state: String?) = TODO()
        override suspend fun mostRecent(): ChatEntity? = TODO()
        override suspend fun getAllIdsOrderedByUpdatedDesc(): List<Long> = TODO()
        override suspend fun recent(limit: Int): List<ChatEntity> = recentChats.take(limit)
        override suspend fun byIds(ids: List<Long>): List<ChatEntity> =
            chatsByIds.filter { it.id in ids }
    }

    private class FakeMessageDao(
        private val visible: List<MessageEntity> = emptyList(),
        private val visibleInChat: List<MessageEntity> = emptyList(),
        private val messagesByIds: List<MessageEntity> = emptyList(),
    ) : MessageDao {
        override suspend fun insert(entity: MessageEntity): Long = TODO()
        override fun observeByChatId(chatId: Long): Flow<List<MessageEntity>> = TODO()
        override fun observeLatestPerChat(): Flow<List<MessageEntity>> = TODO()
        override suspend fun deleteByChatId(chatId: Long) = TODO()
        override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) = TODO()
        override suspend fun clearAllStreaming(): Int = TODO()
        override suspend fun updateContent(messageId: Long, content: String) = TODO()
        override suspend fun deleteById(messageId: Long) = TODO()
        override suspend fun getLastUserMessage(chatId: Long): String? = TODO()
        override suspend fun deleteAll() = TODO()
        override suspend fun recentVisible(limit: Int): List<MessageEntity> = visible.take(limit)
        override suspend fun recentVisibleInChat(chatId: Long, limit: Int): List<MessageEntity> =
            visibleInChat.filter { it.chatId == chatId }.take(limit)
        override suspend fun byIds(ids: List<Long>): List<MessageEntity> =
            messagesByIds.filter { it.id in ids }
    }

    private class FakeVectorDao(
        private val recentVectors: List<ChatMessageVectorEntity> = emptyList(),
    ) : ChatMessageVectorDao {
        override suspend fun upsert(entity: ChatMessageVectorEntity) = TODO()
        override suspend fun upsertAll(entities: List<ChatMessageVectorEntity>) = TODO()
        override suspend fun get(messageId: Long): ChatMessageVectorEntity? = TODO()
        override suspend fun recent(limit: Int): List<ChatMessageVectorEntity> = recentVectors.take(limit)
        override suspend fun recentInChat(chatId: Long, limit: Int): List<ChatMessageVectorEntity> =
            recentVectors.take(limit)
        override suspend fun missingMessagesForEmbedding(limit: Int, minLength: Int): List<MessageEntity> = TODO()
        override suspend fun messageForEmbedding(messageId: Long): MessageEntity? = TODO()
        override suspend fun count(): Int = TODO()
        override suspend fun deleteByMessage(messageId: Long) = TODO()
    }
}
