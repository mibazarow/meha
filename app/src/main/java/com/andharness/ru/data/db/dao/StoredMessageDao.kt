package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.StoredMessageEntity

/**
 * Room access to stored messages (table `stored_messages`).
 *
 * The FTS4 `MATCH` candidate lookup lives in a separate DAO ([StoredMessageFtsDao]), mirroring the
 * transcript split: this DAO is the full-scan path, FTS is only the cheap pre-filter. `recent`
 * filters with nullable parameters so one query serves the un-filtered, source-only, person-only and
 * combined cases.
 */
@Dao
interface StoredMessageDao {

    @Insert
    suspend fun insert(entity: StoredMessageEntity): Long

    @Query("SELECT * FROM stored_messages WHERE id = :id")
    suspend fun getById(id: Long): StoredMessageEntity?

    /**
     * Newest messages first, optionally restricted to one [sourceType] and/or one [personId]. A
     * `null` filter means "no restriction" (`:x IS NULL OR col = :x`); order is `at DESC, id DESC`.
     */
    @Query(
        "SELECT * FROM stored_messages " +
            "WHERE (:sourceType IS NULL OR sourceType = :sourceType) " +
            "AND (:personId IS NULL OR personId = :personId) " +
            "ORDER BY at DESC, id DESC LIMIT :limit"
    )
    suspend fun recent(limit: Int, sourceType: String?, personId: Long?): List<StoredMessageEntity>

    /** Messages of one conversation in chronological order. */
    @Query(
        "SELECT * FROM stored_messages WHERE conversationKey = :key " +
            "ORDER BY at ASC, id ASC LIMIT :limit"
    )
    suspend fun forConversation(key: String, limit: Int): List<StoredMessageEntity>

    /** Resolves FTS candidate ids to full message rows. */
    @Query("SELECT * FROM stored_messages WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<StoredMessageEntity>

    /** The producer-reference dedup lookup; `externalRef` is UNIQUE, so at most one row matches. */
    @Query("SELECT * FROM stored_messages WHERE externalRef = :ref LIMIT 1")
    suspend fun findByExternalRef(ref: String): StoredMessageEntity?

    @Query("SELECT COUNT(*) FROM stored_messages WHERE personId = :personId")
    suspend fun countForPerson(personId: Long): Int

    @Query("DELETE FROM stored_messages WHERE personId = :personId")
    suspend fun deleteByPerson(personId: Long): Int

    @Query("DELETE FROM stored_messages WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM stored_messages")
    suspend fun deleteAll(): Int
}
