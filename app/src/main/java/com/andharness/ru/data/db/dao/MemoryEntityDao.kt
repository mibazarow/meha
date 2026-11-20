package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.GenericEntity
import com.andharness.ru.data.db.entity.MemoryEntityLinkEntity

/**
 * DAO for the generic entity graph: the `entities` node table and the `memory_entities` join table.
 * Nodes are upserted by [GenericEntity.normalizedName] (unique index); links are keyed by
 * (`noteId`, `entityId`) and a re-observation replaces the row ([upsertLinks] with REPLACE).
 *
 * References:
 * - Room entity/indices: https://developer.android.com/training/data-storage/room/defining-data
 * - Room `@Insert` return types and conflict strategies:
 *   https://developer.android.com/training/data-storage/room/accessing-data
 */
@Dao
interface MemoryEntityDao {

    /**
     * Inserts only the entities that do not exist yet (unique `normalizedName`); returns one row id
     * per input in order, with `-1` for the ones that already existed. The caller then resolves the
     * skipped names with [byNormalizedNames].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<GenericEntity>): List<Long>

    @Query("SELECT * FROM entities WHERE normalizedName IN (:names)")
    suspend fun byNormalizedNames(names: List<String>): List<GenericEntity>

    /** Marks already-known entities as observed again at [at]. */
    @Query("UPDATE entities SET lastSeenAt = :at WHERE id IN (:ids)")
    suspend fun touchLastSeen(ids: List<Long>, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLinks(links: List<MemoryEntityLinkEntity>)

    @Query("SELECT DISTINCT entityId FROM memory_entities WHERE noteId IN (:noteIds)")
    suspend fun entityIdsForNotes(noteIds: List<Long>): List<Long>

    @Query(
        "SELECT noteId FROM memory_entities WHERE entityId = :entityId " +
            "ORDER BY weight DESC, noteId DESC LIMIT :limit"
    )
    suspend fun noteIdsForEntity(entityId: Long, limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM memory_entities WHERE noteId = :noteId")
    suspend fun countForNote(noteId: Long): Int

    @Query("DELETE FROM memory_entities WHERE noteId = :noteId")
    suspend fun deleteForNote(noteId: Long): Int

    /** Bounded scan of `kind="memory"` notes that have no join row yet, oldest id first. */
    @Query(
        "SELECT n.id AS noteId, n.text AS text, n.tags AS tags FROM notes n WHERE n.kind = 'memory' " +
            "AND NOT EXISTS (SELECT 1 FROM memory_entities me WHERE me.noteId = n.id) " +
            "ORDER BY n.id ASC LIMIT :limit"
    )
    suspend fun memoriesMissingLinks(limit: Int): List<MissingEntityLinkMemory>
}
