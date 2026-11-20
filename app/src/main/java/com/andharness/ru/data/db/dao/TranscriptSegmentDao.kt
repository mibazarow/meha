package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.TranscriptSegmentEntity

/** Room access to transcript segments (table `transcript_segments`). */
@Dao
interface TranscriptSegmentDao {

    @Insert
    suspend fun insert(entity: TranscriptSegmentEntity): Long

    @Insert
    suspend fun insertAll(entities: List<TranscriptSegmentEntity>): List<Long>

    /** Segments of one recording in playback order; this is the transcript the UI renders. */
    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY startMs ASC, id ASC")
    suspend fun byRecording(recordingId: Long): List<TranscriptSegmentEntity>

    /** Resolves FTS candidate ids to full segment rows, preserving no particular order. */
    @Query("SELECT * FROM transcript_segments WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TranscriptSegmentEntity>

    /** Assigns (or clears, with `null`) the diarised speaker of one segment. */
    @Query("UPDATE transcript_segments SET speakerId = :speakerId WHERE id = :id")
    suspend fun updateSpeaker(id: Long, speakerId: Long?): Int

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long): Int
}
