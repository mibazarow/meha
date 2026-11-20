package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.MemoryPersonEntity

/**
 * DAO for the `memory_persons` join table. One row per (memory, person) pair, keyed by
 * (`noteId`, `personId`); a re-observation replaces the row ([upsertAll] with REPLACE).
 */
@Dao
interface MemoryPersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MemoryPersonEntity>)

    @Query("SELECT DISTINCT personId FROM memory_persons WHERE noteId IN (:noteIds)")
    suspend fun personIdsForNotes(noteIds: List<Long>): List<Long>

    @Query(
        "SELECT noteId FROM memory_persons WHERE personId = :personId " +
            "ORDER BY weight DESC, noteId DESC LIMIT :limit"
    )
    suspend fun noteIdsForPerson(personId: Long, limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM memory_persons WHERE noteId = :noteId")
    suspend fun countForNote(noteId: Long): Int

    @Query("DELETE FROM memory_persons WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long): Int

    @Query("DELETE FROM memory_persons")
    suspend fun deleteAll()

    /** Bounded scan of `kind="memory"` notes that have no join row yet, oldest id first. */
    @Query(
        "SELECT n.id AS noteId, n.text AS text FROM notes n WHERE n.kind = 'memory' " +
            "AND NOT EXISTS (SELECT 1 FROM memory_persons mp WHERE mp.noteId = n.id) " +
            "ORDER BY n.id ASC LIMIT :limit"
    )
    suspend fun memoriesMissingLinks(limit: Int): List<MissingLinkMemory>
}
