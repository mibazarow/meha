package com.andharness.ru.domain.person

/**
 * Persistence for [Person] rows of the unified personal base (update.md §4.3).
 *
 * Implemented on top of the Room `persons` table; the schema change that introduced it ships with an
 * explicit, non-destructive migration (no destructive fallback), see
 * https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
interface PersonRepository {

    /**
     * Creates a person with [displayName] and returns its row id.
     *
     * @param notes free-form notes; empty by default.
     */
    suspend fun insert(displayName: String, notes: String = ""): Long

    suspend fun get(id: Long): Person?

    /** Case-insensitive substring search over display names. */
    suspend fun searchByName(query: String, limit: Int = 20): List<Person>

    /** People carrying [alias] (case-insensitive exact alias match). */
    suspend fun byAlias(alias: String, limit: Int = 20): List<Person>

    /** Most recently updated people, newest first. */
    suspend fun recent(limit: Int = 20): List<Person>

    /** Persists [person]'s display name/notes and stamps [Person.updatedAt]. */
    suspend fun update(person: Person)

    /** Deletes the person row; returns the number of removed rows. */
    suspend fun delete(id: Long): Int

    /**
     * Merges `fromId` into `intoId`: moves identities, interactions and aliases over, drops
     * duplicates, then deletes the source person. Whole thing runs in one transaction so a failure
     * cannot leave rows pointing at a deleted person.
     *
     * @return `true` when both people existed and the merge was applied.
     */
    suspend fun merge(fromId: Long, intoId: Long): Boolean

    suspend fun count(): Int
}
