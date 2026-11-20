package com.andharness.ru.domain.ssh

/**
 * Persistence for the SSH server registry (table `ssh_servers`, update.md §5.2).
 *
 * The repository only ever handles non-secret metadata: passwords, passphrases and key material are
 * out of scope here and are managed by the secure store (update.md §5.4).
 */
interface SshServerRepository {

    /** Every registered server, ordered by name (case-insensitive). */
    suspend fun all(): List<SshServer>

    suspend fun byId(id: Long): SshServer?

    /** Case-insensitive exact-name lookup. */
    suspend fun byName(name: String): SshServer?

    /**
     * Registers a new server.
     *
     * @return the new row id, or `null` when a server with that name already exists (the unique
     *   index refuses it — the caller must report the conflict, never overwrite silently).
     */
    suspend fun insert(server: SshServer): Long?

    /** Removes one server; returns the number of deleted rows. */
    suspend fun remove(id: Long): Int

    /**
     * Renames one server.
     *
     * @return the number of updated rows: `0` when the id does not exist or [name] is blank.
     *   Callers must check [byName] first and report a name clash themselves (the unique index
     *   makes an unguarded update throw, never silently overwrite).
     */
    suspend fun rename(id: Long, name: String): Int

    /**
     * Updates the non-secret connection fields of one server ([host], [port], [user] are the full
     * new values — the caller merges the provided fields with the existing ones).
     *
     * @return the number of updated rows: `0` when the id does not exist, a value is blank, or the
     *   port is out of range.
     */
    suspend fun update(id: Long, host: String, port: Int, user: String): Int
}
