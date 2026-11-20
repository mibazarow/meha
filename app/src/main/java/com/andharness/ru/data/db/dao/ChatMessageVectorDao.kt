package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.ChatMessageVectorEntity
import com.andharness.ru.data.db.entity.MessageEntity

/**
 * DAO for the dense chat-message embedding store. One row per message, keyed by that message's id;
 * a re-embed replaces the row ([upsert] / [upsertAll] with REPLACE), which also makes the backfill
 * idempotent.
 *
 * Mirrors [StoredMessageVectorDao]; the two extra reads ([missingMessagesForEmbedding] and
 * [messageForEmbedding]) join the vectors table against `messages` so the indexer needs no extra
 * round-trip (no N+1). `@Insert(onConflict = OnConflictStrategy.REPLACE)` is the documented insert
 * conflict strategy; Room validates the SQL at compile time
 * (https://developer.android.com/training/data-storage/room/accessing-data).
 *
 * The `length(trim(content)) >= :minLength` filter keeps too-short bodies out of the candidate
 * window at the SQL level, so a run of short messages can never starve older long ones; the indexer
 * applies the same rule again in Kotlin as defence in depth.
 */
@Dao
interface ChatMessageVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChatMessageVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChatMessageVectorEntity>)

    @Query("SELECT * FROM chat_message_vectors WHERE messageId = :messageId")
    suspend fun get(messageId: Long): ChatMessageVectorEntity?

    /**
     * The newest chat embeddings, at most [limit], highest message id first — the bounded candidate
     * pool for semantic chat search.
     */
    @Query("SELECT * FROM chat_message_vectors ORDER BY messageId DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatMessageVectorEntity>

    /**
     * The newest [limit] chat embeddings OF ONE CHAT (joined to `messages`), highest message id
     * first — the bounded candidate pool for in-chat semantic search. Scoping at the SQL level means
     * a busy chat can never push another chat's vectors out of the window.
     */
    @Query(
        "SELECT v.* FROM chat_message_vectors v " +
            "INNER JOIN messages m ON m.id = v.messageId " +
            "WHERE m.chatId = :chatId ORDER BY v.messageId DESC LIMIT :limit"
    )
    suspend fun recentInChat(chatId: Long, limit: Int): List<ChatMessageVectorEntity>

    /**
     * `user` / `assistant` messages that still have no embedding and are long enough to be worth an
     * inference, oldest id first. `tool` rows and short/blank bodies are excluded so the backfill
     * always makes forward progress.
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "LEFT JOIN chat_message_vectors v ON v.messageId = m.id " +
            "WHERE v.messageId IS NULL " +
            "AND m.role IN ('user', 'assistant') " +
            "AND length(trim(m.content)) >= :minLength " +
            "ORDER BY m.id ASC LIMIT :limit"
    )
    suspend fun missingMessagesForEmbedding(limit: Int, minLength: Int): List<MessageEntity>

    /** One message by id, for indexing at the moment it becomes final. */
    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun messageForEmbedding(messageId: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM chat_message_vectors")
    suspend fun count(): Int

    @Query("DELETE FROM chat_message_vectors WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: Long)
}
