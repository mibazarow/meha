package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.PersonIdentityEntity

/** Room access to person identities (table `person_identities`, update.md §4.3). */
@Dao
interface PersonIdentityDao {

    /**
     * Inserts an identity, ignoring a row that already has the same
     * (`type`, `value`, `appPackage`) triple. Returns the new row id, or `-1` on conflict — callers
     * that need the existing id should fall back to [byValue].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PersonIdentityEntity): Long

    @Query(
        "SELECT * FROM person_identities WHERE type = :type AND value = :value " +
            "AND appPackage = :appPackage LIMIT 1"
    )
    suspend fun byValue(type: String, value: String, appPackage: String): PersonIdentityEntity?

    @Query("SELECT * FROM person_identities WHERE personId = :personId ORDER BY id ASC")
    suspend fun forPerson(personId: Long): List<PersonIdentityEntity>

    @Query("UPDATE person_identities SET personId = :personId WHERE id = :id")
    suspend fun link(id: Long, personId: Long): Int

    @Query("DELETE FROM person_identities WHERE id = :id")
    suspend fun delete(id: Long): Int

    /**
     * Re-points every identity of `fromId` at `intoId` during a merge; conflicting rows (the target
     * already has that identity) are left in place and dropped by [deleteForPerson].
     */
    @Query("UPDATE OR IGNORE person_identities SET personId = :intoId WHERE personId = :fromId")
    suspend fun reassign(fromId: Long, intoId: Long): Int

    @Query("DELETE FROM person_identities WHERE personId = :personId")
    suspend fun deleteForPerson(personId: Long): Int
}
