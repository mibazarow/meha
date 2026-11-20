package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.SshServerEntity

/** Room access to the registered SSH servers (table `ssh_servers`, update.md §5.2). */
@Dao
interface SshServerDao {

    /**
     * Inserts a server, ignoring a duplicate [SshServerEntity.name] (the unique index). Returns the
     * new row id, or `-1` on conflict — the caller reports that instead of overwriting silently.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SshServerEntity): Long

    @Query("SELECT * FROM ssh_servers ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun all(): List<SshServerEntity>

    @Query("SELECT * FROM ssh_servers WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SshServerEntity?

    /** Case-insensitive exact name lookup (the unique index makes this return at most one row). */
    @Query("SELECT * FROM ssh_servers WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): SshServerEntity?

    @Query("DELETE FROM ssh_servers WHERE id = :id")
    suspend fun delete(id: Long): Int

    /**
     * Renames one server; returns the number of updated rows (`0` when the id does not exist).
     * The unique index on `name` is what refuses a clash — callers must pre-check the new name and
     * report the conflict instead of letting SQLite throw.
     */
    @Query("UPDATE ssh_servers SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    /** Updates the non-secret connection fields of one server; returns the number of updated rows. */
    @Query("UPDATE ssh_servers SET host = :host, port = :port, user = :user WHERE id = :id")
    suspend fun update(id: Long, host: String, port: Int, user: String): Int
}
