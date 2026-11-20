package com.andharness.ru.domain.person

/**
 * Persistence for [Interaction] rows (table `interactions`, update.md §4.3).
 *
 * Only metadata + a short summary are ever passed here — the message body is never stored
 * (update.md §4.9).
 */
interface InteractionRepository {

    /** Stores an interaction (the [Interaction.id] is ignored) and returns its row id. */
    suspend fun insert(interaction: Interaction): Long

    /** Newest-first interactions with one person. */
    suspend fun recentFor(personId: Long, limit: Int = 20): List<Interaction>

    /** Newest-first interactions across all people. */
    suspend fun recentOverall(limit: Int = 20): List<Interaction>

    /**
     * Substring search over the stored metadata (title/summary/source app) or the owning person's
     * identities (phone/email/messenger), newest first.
     */
    suspend fun search(query: String, limit: Int = 20): List<Interaction>

    /** Same as [search] but restricted to one person, newest first. */
    suspend fun searchForPerson(personId: Long, query: String, limit: Int = 20): List<Interaction>

    /** Whether the producer's [externalRef] was already indexed for the person (ingest dedup). */
    suspend fun existsByExternalRef(personId: Long, externalRef: String): Boolean

    suspend fun countFor(personId: Long): Int

    suspend fun deleteFor(personId: Long): Int
}
