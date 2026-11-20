package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.PersonAliasEntity

/** Room access to person aliases (table `person_aliases`, update.md §4.3). */
@Dao
interface PersonAliasDao {

    /**
     * Inserts an alias, ignoring a duplicate of the same (`personId`, `alias`) pair. Returns the new
     * row id, or `-1` on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PersonAliasEntity): Long

    @Query("SELECT * FROM person_aliases WHERE personId = :personId ORDER BY alias COLLATE NOCASE ASC")
    suspend fun forPerson(personId: Long): List<PersonAliasEntity>

    /** Bounded scan of every alias, grouped naturally by personId (linking/backfill helpers). */
    @Query(
        "SELECT * FROM person_aliases ORDER BY personId ASC, alias COLLATE NOCASE ASC LIMIT :limit"
    )
    suspend fun all(limit: Int): List<PersonAliasEntity>

    /** Case-insensitive exact alias lookup (the fuzzy step is done in the resolver, not in SQL). */
    @Query("SELECT * FROM person_aliases WHERE alias = :alias COLLATE NOCASE ORDER BY id ASC")
    suspend fun byAlias(alias: String): List<PersonAliasEntity>

    @Query("DELETE FROM person_aliases WHERE personId = :personId AND alias = :alias COLLATE NOCASE")
    suspend fun delete(personId: Long, alias: String): Int

    /**
     * Re-points every alias of `fromId` at `intoId` during a merge; an alias both people already
     * have is left in place and dropped by [deleteForPerson].
     */
    @Query("UPDATE OR IGNORE person_aliases SET personId = :intoId WHERE personId = :fromId")
    suspend fun reassign(fromId: Long, intoId: Long): Int

    @Query("DELETE FROM person_aliases WHERE personId = :personId")
    suspend fun deleteForPerson(personId: Long): Int
}
