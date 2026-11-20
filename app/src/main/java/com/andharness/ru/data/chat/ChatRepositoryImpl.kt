package com.andharness.ru.data.chat

import com.andharness.ru.data.db.dao.ChatDao
import com.andharness.ru.data.db.dao.MessageDao
import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.pinnedAtFor
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ChatRepository] backed by [ChatDao] and [MessageDao].
 *
 * Timestamps use [System.currentTimeMillis] because java.time APIs are not reliably
 * available on minSdk 26 without desugaring (see
 * https://developer.android.com/guide/topics/java/versions#java-8-api-support).
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val chatMessageVectorIndexer: ChatMessageVectorIndexer
) : ChatRepository {

    override fun observeChats(): Flow<List<ChatEntity>> = chatDao.observeAll()

    override fun observeChat(chatId: Long): Flow<ChatEntity?> = chatDao.observeById(chatId)

    override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> =
        messageDao.observeByChatId(chatId)

    override fun observeLastMessages(): Flow<List<MessageEntity>> =
        messageDao.observeLatestPerChat()

    override suspend fun createChat(title: String): Long {
        val now = System.currentTimeMillis()
        return chatDao.insert(
            ChatEntity(
                title = title,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun chatExists(chatId: Long): Boolean = chatDao.countById(chatId) > 0

    override suspend fun addMessage(
        chatId: Long,
        role: String,
        content: String,
        toolName: String?
    ): Long = addMessageWithPayload(chatId, role, content, toolName, payload = null)

    override suspend fun addMessageWithPayload(
        chatId: Long,
        role: String,
        content: String,
        toolName: String?,
        payload: String?
    ): Long {
        val id = messageDao.insert(
            MessageEntity(
                chatId = chatId,
                role = role,
                content = content,
                toolName = toolName,
                createdAt = System.currentTimeMillis(),
                payload = payload
            )
        )
        // A non-streaming user/assistant message is final the moment it is written, so index it now.
        // The indexer is fire-and-forget on the process scope: it cannot block or fail this write.
        // The streaming assistant shell (blank body, indexed later on `setStreaming(false)`) and
        // `tool` rows are skipped here.
        if ((role == ROLE_USER || role == ROLE_ASSISTANT) && content.isNotBlank()) {
            chatMessageVectorIndexer.indexAsync(id)
        }
        return id
    }

    override suspend fun updateMessageContent(messageId: Long, content: String) {
        messageDao.updateContent(messageId, content)
    }

    override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) {
        messageDao.setStreaming(messageId, isStreaming)
        // `isStreaming = false` marks a streamed assistant message final: its body is now authoritative
        // and can be embedded. Fire-and-forget, so the write/UI is never blocked.
        if (!isStreaming) chatMessageVectorIndexer.indexAsync(messageId)
    }

    override suspend fun clearStaleStreaming(): Int = messageDao.clearAllStreaming()

    override suspend fun deleteChat(chatId: Long) {
        chatDao.deleteById(chatId)
    }

    override suspend fun renameChat(chatId: Long, title: String) {
        val now = System.currentTimeMillis()
        chatDao.updateTitle(chatId, title, now)
        chatDao.updateUpdatedAt(chatId, now)
    }

    override suspend fun updateSummary(chatId: Long, summary: String) {
        chatDao.updateSummary(chatId, summary)
    }

    override suspend fun updateContextState(chatId: Long, state: String?) {
        chatDao.updateContextState(chatId, state)
    }

    override suspend fun pruneToLimit(keep: Int) {
        if (keep <= 0) return
        val ids = chatDao.getAllIdsOrderedByUpdatedDesc()
        ids.drop(keep).forEach { id ->
            messageDao.deleteByChatId(id)
            chatDao.deleteById(id)
        }
    }

    override suspend fun deleteAllChats() {
        chatDao.deleteAll()
        messageDao.deleteAll()
    }

    override suspend fun setPinned(chatId: Long, pinned: Boolean) {
        chatDao.updatePinnedAt(chatId, pinnedAtFor(pinned, System.currentTimeMillis()))
    }

    override suspend fun clearMessages(chatId: Long) {
        messageDao.deleteByChatId(chatId)
    }

    override suspend fun mostRecentChatId(): Long? = chatDao.mostRecent()?.id

    private companion object {
        /** The two visible transcript roles whose messages are worth embedding for semantic search. */
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
