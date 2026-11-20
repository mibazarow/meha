package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.SpeakerVoiceEmbeddingEntity

/** Room access to the raw voice-embedding samples (table `speaker_voice_embeddings`). */
@Dao
interface SpeakerVoiceEmbeddingDao {

    @Insert
    suspend fun insert(entity: SpeakerVoiceEmbeddingEntity): Long

    @Insert
    suspend fun insertAll(entities: List<SpeakerVoiceEmbeddingEntity>): List<Long>

    /** Samples of one speaker, oldest first (the order the centroid was built in). */
    @Query("SELECT * FROM speaker_voice_embeddings WHERE speakerId = :speakerId ORDER BY createdAt ASC, id ASC")
    suspend fun bySpeaker(speakerId: Long): List<SpeakerVoiceEmbeddingEntity>

    @Query("DELETE FROM speaker_voice_embeddings WHERE speakerId = :speakerId")
    suspend fun deleteBySpeaker(speakerId: Long): Int
}
