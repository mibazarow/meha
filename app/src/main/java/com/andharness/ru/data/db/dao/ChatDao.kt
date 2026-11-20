package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChatEntity): Long

    @Query("SELECT * FROM chats WHERE id = :id")
    fun getById(id: Long): ChatEntity?

    /**
     * Number of chats with [id] (0 or 1). Used to validate that an objective's bound chat still
     * exists before an agent turn writes to it: a deleted chat cascades its messages away, so a
     * dangling `chatId` would otherwise fail the `messages.chatId` FOREIGN KEY. A read-only query —
     * no schema change, no migration.
     */
    @Query("SELECT COUNT(*) FROM chats WHERE id = :id")
    suspend fun countById(id: Long): Int

    /**
     * The chat list: pinned chats first, then by recency. `pinnedAt > 0` means pinned, so the
     * `CASE` sorts pinned (1) before unpinned (0) while `updatedAt DESC` keeps the usual order
     * inside each group. This is list ordering only — `mostRecent()` keeps `updatedAt` semantics.
     * Reference: https://developer.android.com/training/data-storage/room/accessing-data
     */
    @Query(
        "SELECT * FROM chats " +
            "ORDER BY CASE WHEN pinnedAt > 0 THEN 1 ELSE 0 END DESC, updatedAt DESC"
    )
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    /** Sets (epoch millis) or clears (0) the pin marker of a chat. */
    @Query("UPDATE chats SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun updatePinnedAt(id: Long, pinnedAt: Long)

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateUpdatedAt(id: Long, updatedAt: Long)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeById(id: Long): Flow<ChatEntity?>

    @Query("UPDATE chats SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    /** Persists the opaque compaction bookkeeping (e.g. the last summarised message id). */
    @Query("UPDATE chats SET contextState = :state WHERE id = :id")
    suspend fun updateContextState(id: Long, state: String?)

    /**
     * The single most recently updated chat. Keeps `chat_recall`'s "most recent" meaning now that
     * [observeAll] floats pinned chats first: pinning a chat must not hijack the default recall
     * target. `LIMIT 1` with an explicit `ORDER BY updatedAt DESC` — ordering is independent of the
     * list query. Reference: https://developer.android.com/training/data-storage/room/accessing-data
     */
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC LIMIT 1")
    suspend fun mostRecent(): ChatEntity?

    @Query("SELECT id FROM chats ORDER BY updatedAt DESC")
    suspend fun getAllIdsOrderedByUpdatedDesc(): List<Long>

    /**
     * The newest [limit] chats (highest `updatedAt` first) — a BOUNDED pool for chat-TITLE search.
     * The caller does the case-insensitive `contains` in Kotlin (SQLite `LIKE` folds case for ASCII
     * only, not Cyrillic).
     * Reference: https://developer.android.com/training/data-storage/room/accessing-data
     */
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatEntity>

    /** Resolves a set of chat ids (e.g. the parents of matched messages) in one round-trip. */
    @Query("SELECT * FROM chats WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChatEntity>
}
