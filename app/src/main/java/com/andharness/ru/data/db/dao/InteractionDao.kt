package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.andharness.ru.data.db.entity.InteractionEntity

/** Room access to person interactions (table `interactions`, update.md §4.3). */
@Dao
interface InteractionDao {

    @Insert
    suspend fun insert(entity: InteractionEntity): Long

    /** Newest-first interactions of one person. */
    @Query("SELECT * FROM interactions WHERE personId = :personId ORDER BY at DESC LIMIT :limit")
    suspend fun recentFor(personId: Long, limit: Int): List<InteractionEntity>

    /** Newest-first interactions across all people (prompt "recent activity" block). */
    @Query("SELECT * FROM interactions ORDER BY at DESC LIMIT :limit")
    suspend fun recentOverall(limit: Int): List<InteractionEntity>

    /**
     * Substring search over the stored metadata (title/summary/source app) **or any identity of the
     * owning person** (phone/email/messenger in `person_identities.value`), newest first.
     *
     * The identity clause is what makes a search by a phone number/email/nick return the person's
     * interactions even though those handles live outside the `interactions` row.
     */
    @Query(
        "SELECT * FROM interactions WHERE title LIKE '%' || :query || '%' " +
            "OR summary LIKE '%' || :query || '%' " +
            "OR sourceApp LIKE '%' || :query || '%' " +
            "OR personId IN (" +
            "SELECT personId FROM person_identities WHERE value LIKE '%' || :query || '%') " +
            "ORDER BY at DESC LIMIT :limit"
    )
    suspend fun search(query: String, limit: Int): List<InteractionEntity>

    /** Same as [search] but restricted to one person, newest first. */
    @Query(
        "SELECT * FROM interactions WHERE personId = :personId AND (" +
            "title LIKE '%' || :query || '%' " +
            "OR summary LIKE '%' || :query || '%' " +
            "OR sourceApp LIKE '%' || :query || '%' " +
            "OR personId IN (" +
            "SELECT personId FROM person_identities WHERE value LIKE '%' || :query || '%')) " +
            "ORDER BY at DESC LIMIT :limit"
    )
    suspend fun searchForPerson(personId: Long, query: String, limit: Int): List<InteractionEntity>

    /** Whether the producer's [externalRef] was already indexed for [personId] (dedup on ingest). */
    @Query(
        "SELECT COUNT(*) FROM interactions WHERE personId = :personId " +
            "AND externalRef = :externalRef AND externalRef IS NOT NULL"
    )
    suspend fun countByExternalRef(personId: Long, externalRef: String): Int

    /** Re-points every interaction of `fromId` at `intoId` during a merge (no unique constraint). */
    @Query("UPDATE interactions SET personId = :intoId WHERE personId = :fromId")
    suspend fun reassign(fromId: Long, intoId: Long): Int

    @Query("DELETE FROM interactions WHERE personId = :personId")
    suspend fun deleteForPerson(personId: Long): Int

    @Query("SELECT COUNT(*) FROM interactions WHERE personId = :personId")
    suspend fun countForPerson(personId: Long): Int
}
