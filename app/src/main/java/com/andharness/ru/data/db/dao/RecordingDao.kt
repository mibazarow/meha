package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.RecordingEntity

/**
 * Room access to the "Communications" recordings (table `recordings`).
 *
 * Status values in the queries are the names of
 * [com.andharness.ru.domain.communications.TranscriptStatus]; callers pass `enum.name`.
 */
@Dao
interface RecordingDao {

    @Insert
    suspend fun insert(entity: RecordingEntity): Long

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    /** Recordings waiting to be (or currently being) transcribed, oldest first. */
    @Query("SELECT * FROM recordings WHERE transcriptStatus = :status ORDER BY createdAt ASC LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int): List<RecordingEntity>

    /** Newest-first recent recordings for the list screen. */
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RecordingEntity>

    /** Recordings attributed to one person, newest first. */
    @Query("SELECT * FROM recordings WHERE personId = :personId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byPerson(personId: Long, limit: Int): List<RecordingEntity>

    /**
     * Moves a recording through the transcription lifecycle, recording why when [status] is
     * `FAILED` ([errorText] is cleared for every other status by passing `null`).
     */
    @Query(
        "UPDATE recordings SET transcriptStatus = :status, errorText = :errorText, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateTranscriptStatus(
        id: Long,
        status: String,
        errorText: String?,
        updatedAt: Long
    ): Int

    /** Sets or clears the "keep regardless of retention" flag. */
    @Query("UPDATE recordings SET keepFlag = :keepFlag, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setKeepFlag(id: Long, keepFlag: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM recordings WHERE transcriptStatus = :status")
    suspend fun countByStatus(status: String): Int
}
