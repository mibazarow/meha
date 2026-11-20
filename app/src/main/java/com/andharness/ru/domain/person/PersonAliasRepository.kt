package com.andharness.ru.domain.person

/**
 * Persistence for [PersonAlias] rows (table `person_aliases`, update.md §4.3/§4.5).
 *
 * Aliases are stored trimmed but not case-folded; matching is case-insensitive. Unlike identities,
 * aliases carry no type, so the fuzzy step of person resolution (update.md §4.5) is where they are
 * actually scored.
 */
interface PersonAliasRepository {

    /**
     * Adds [alias] (trimmed) to [personId].
     *
     * @return the alias id — the existing one when that person already has this alias — or `null`
     *   when [alias] is blank.
     */
    suspend fun insert(personId: Long, alias: String): Long?

    suspend fun forPerson(personId: Long): List<PersonAlias>

    /** Every alias matching [alias] case-insensitively (reverse lookup: who is called X). */
    suspend fun byAlias(alias: String): List<PersonAlias>

    /** Removes one alias from a person; returns the number of removed rows. */
    suspend fun delete(personId: Long, alias: String): Int

    suspend fun deleteFor(personId: Long): Int
}
