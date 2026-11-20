package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.PersonEntity

/** Room access to persons of the unified personal base (table `persons`, update.md §4.3). */
@Dao
interface PersonDao {

    @Insert
    suspend fun insert(entity: PersonEntity): Long

    @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): PersonEntity?

    /** Case-insensitive substring match on the display name. */
    @Query(
        "SELECT * FROM persons WHERE displayName LIKE '%' || :query || '%' " +
            "ORDER BY displayName COLLATE NOCASE ASC LIMIT :limit"
    )
    suspend fun searchByName(query: String, limit: Int): List<PersonEntity>

    /** Batch lookup used for reverse alias → person resolution. */
    @Query("SELECT * FROM persons WHERE id IN (:ids) ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun byIds(ids: List<Long>): List<PersonEntity>

    /** Most recently touched persons (prompt summary / recent-context block). */
    @Query("SELECT * FROM persons ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PersonEntity>

    /** Bounded scan of every person, oldest id first (linking/backfill helpers). */
    @Query("SELECT * FROM persons ORDER BY id ASC LIMIT :limit")
    suspend fun all(limit: Int): List<PersonEntity>

    /** Renames/renotes a person; returns the number of updated rows (0 = no such person). */
    @Query(
        "UPDATE persons SET displayName = :displayName, notes = :notes, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateFields(id: Long, displayName: String, notes: String, updatedAt: Long): Int

    @Query("DELETE FROM persons WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM persons")
    suspend fun count(): Int
}
