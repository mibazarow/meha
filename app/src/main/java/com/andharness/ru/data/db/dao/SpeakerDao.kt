package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.SpeakerEntity

/** Room access to the speaker registry (table `speakers`). */
@Dao
interface SpeakerDao {

    @Insert
    suspend fun insert(entity: SpeakerEntity): Long

    /** Insert-or-replace by primary key; used when recomputing a centroid. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SpeakerEntity)

    /** Like [upsert] but returns the resulting row id (new id when the entity has `id = 0`). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReturningId(entity: SpeakerEntity): Long

    @Query("SELECT * FROM speakers WHERE id = :id")
    suspend fun get(id: Long): SpeakerEntity?

    /** All speakers, device owner first, then by creation time. */
    @Query("SELECT * FROM speakers ORDER BY isSelf DESC, createdAt ASC")
    suspend fun list(): List<SpeakerEntity>

    @Query("SELECT * FROM speakers WHERE isSelf = 1 LIMIT 1")
    suspend fun self(): SpeakerEntity?

    @Query("SELECT * FROM speakers WHERE personId = :personId LIMIT 1")
    suspend fun byPerson(personId: Long): SpeakerEntity?

    /** Links (or unlinks, with `null`) a speaker cluster to a person. */
    @Query("UPDATE speakers SET personId = :personId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun linkToPerson(id: Long, personId: Long?, updatedAt: Long): Int

    /** Sets (or clears, with `null`) the display name of a speaker cluster. */
    @Query("UPDATE speakers SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setName(id: Long, name: String?, updatedAt: Long): Int

    /**
     * Stores a freshly recomputed voice centroid and its provenance **without** touching
     * [sampleCount]/[confidence] (the caller owns those).
     */
    @Query(
        "UPDATE speakers SET centroid = :centroid, voiceDim = :voiceDim, voiceModel = :voiceModel, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setCentroidVector(
        id: Long,
        centroid: ByteArray?,
        voiceDim: Int?,
        voiceModel: String?,
        updatedAt: Long
    ): Int

    /** Stores a freshly recomputed voice centroid and its provenance. */
    @Query(
        "UPDATE speakers SET centroid = :centroid, voiceDim = :voiceDim, voiceModel = :voiceModel, " +
            "sampleCount = :sampleCount, confidence = :confidence, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setCentroid(
        id: Long,
        centroid: ByteArray?,
        voiceDim: Int?,
        voiceModel: String?,
        sampleCount: Int,
        confidence: Float?,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM speakers WHERE id = :id")
    suspend fun delete(id: Long): Int
}
