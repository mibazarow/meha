package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.StoredMessageVectorEntity

/**
 * DAO for the dense stored-message embedding store. One row per message, keyed by that message's id;
 * a re-embed replaces the row ([upsert] with REPLACE).
 */
@Dao
interface StoredMessageVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StoredMessageVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<StoredMessageVectorEntity>)

    @Query("SELECT * FROM stored_message_vectors WHERE messageId = :messageId")
    suspend fun get(messageId: Long): StoredMessageVectorEntity?

    @Query("SELECT * FROM stored_message_vectors WHERE messageId IN (:messageIds)")
    suspend fun byIds(messageIds: List<Long>): List<StoredMessageVectorEntity>

    /**
     * The newest stored embeddings, at most [limit], highest message id first — the bounded candidate
     * pool for semantic message search.
     */
    @Query("SELECT * FROM stored_message_vectors ORDER BY messageId DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<StoredMessageVectorEntity>

    /** Messages that still have no embedding row, oldest id first, so the worker knows what to embed. */
    @Query(
        "SELECT s.id FROM stored_messages s " +
            "LEFT JOIN stored_message_vectors v ON v.messageId = s.id " +
            "WHERE v.messageId IS NULL ORDER BY s.id ASC LIMIT :limit"
    )
    suspend fun missingAllMessageIds(limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM stored_message_vectors")
    suspend fun count(): Int

    @Query("DELETE FROM stored_message_vectors WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: Long)
}
