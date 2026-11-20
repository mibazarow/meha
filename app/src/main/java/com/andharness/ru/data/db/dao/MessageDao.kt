package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    fun observeByChatId(chatId: Long): Flow<List<MessageEntity>>

    /**
     * The last *visible* message of every chat, for the chat-list preview. One row per chat — no
     * per-chat round trip (avoids N+1 on the list).
     *
     * `id` is the autoincrementing primary key, so `MAX(id)` per group is the most recently inserted
     * message; ordering by `createdAt` would tie on messages written in the same millisecond.
     * `tool` rows are excluded: the transcript (see `ChatMessageList`) never renders them, so a
     * preview must not surface a raw tool result as the "last message".
     *
     * Reference: Room `@Query` + reactive `Flow` results —
     * https://developer.android.com/training/data-storage/room/async-queries
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "INNER JOIN (SELECT chatId, MAX(id) AS maxId FROM messages " +
            "WHERE role IN ('user', 'assistant') GROUP BY chatId) latest " +
            "ON m.id = latest.maxId"
    )
    fun observeLatestPerChat(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: Long)

    @Query("UPDATE messages SET isStreaming = :isStreaming WHERE id = :messageId")
    suspend fun setStreaming(messageId: Long, isStreaming: Boolean)

    /**
     * Resets every persisted `isStreaming=1` row. A run cannot survive a process restart, so any
     * such flag found at startup is stale and would otherwise leave a spinner/stop button on an
     * already-finished message. Returns the number of rows that were reset.
     */
    @Query("UPDATE messages SET isStreaming = 0 WHERE isStreaming = 1")
    suspend fun clearAllStreaming(): Int

    @Query("UPDATE messages SET content = :content WHERE id = :messageId")
    suspend fun updateContent(messageId: Long, content: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    /**
     * The text of the most recent `user` message of a chat — used by
     * [com.andharness.ru.data.crash.CrashGuard] to snapshot an unfinished task on crash
     * (update.md §1.4).
     */
    @Query(
        "SELECT content FROM messages WHERE chatId = :chatId AND role = 'user' " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    suspend fun getLastUserMessage(chatId: Long): String?

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /**
     * The newest [limit] `user`/`assistant` messages across all chats (highest id first) — a BOUNDED
     * lexical candidate pool for chat search. `tool` rows are excluded because the transcript never
     * renders them. The caller filters by case-insensitive `contains` in Kotlin, because SQLite's
     * `LIKE` folds case for ASCII only (not Cyrillic).
     * Reference: https://developer.android.com/training/data-storage/room/accessing-data
     */
    @Query(
        "SELECT * FROM messages WHERE role IN ('user', 'assistant') " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun recentVisible(limit: Int): List<MessageEntity>

    /** The newest [limit] `user`/`assistant` messages of ONE chat; lexical pool for in-chat search. */
    @Query(
        "SELECT * FROM messages WHERE chatId = :chatId AND role IN ('user', 'assistant') " +
            "ORDER BY id DESC LIMIT :limit"
    )
    suspend fun recentVisibleInChat(chatId: Long, limit: Int): List<MessageEntity>

    /** Resolves a set of message ids (e.g. the semantic candidates) in one round-trip (no N+1). */
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MessageEntity>
}
