package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.TranscriptVectorEntity

/**
 * DAO for the dense transcript-embedding store. One row per segment, keyed by that segment's id; a
 * re-embed replaces the row ([upsert] with REPLACE).
 */
@Dao
interface TranscriptVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TranscriptVectorEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TranscriptVectorEntity>)

    @Query("SELECT * FROM transcript_vectors WHERE segmentId = :segmentId")
    suspend fun get(segmentId: Long): TranscriptVectorEntity?

    @Query("SELECT * FROM transcript_vectors WHERE segmentId IN (:segmentIds)")
    suspend fun byIds(segmentIds: List<Long>): List<TranscriptVectorEntity>

    /**
     * The newest stored embeddings, at most [limit], highest segment id first. Segment ids grow with
     * insertion order, so this is the most recently transcribed pool — the bounded candidate set the
     * semantic transcript search scans.
     */
    @Query("SELECT * FROM transcript_vectors ORDER BY segmentId DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TranscriptVectorEntity>

    /** Any segments (across all recordings) that still have no embedding row, oldest id first. */
    @Query(
        "SELECT s.id FROM transcript_segments s " +
            "LEFT JOIN transcript_vectors v ON v.segmentId = s.id " +
            "WHERE v.segmentId IS NULL ORDER BY s.id ASC LIMIT :limit"
    )
    suspend fun missingAllSegmentIds(limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM transcript_vectors")
    suspend fun count(): Int

    @Query("DELETE FROM transcript_vectors WHERE segmentId = :segmentId")
    suspend fun delete(segmentId: Long)
}
