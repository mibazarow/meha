package com.andharness.ru.domain.communications

/**
 * Persistence for [Recording] rows (table `recordings`).
 *
 * All methods are `suspend`; implementations dispatch to the Room DAO. Status values round-trip
 * through the enum [name][TranscriptStatus.name] so the table stays stable across versions.
 */
interface RecordingsRepository {

    /**
     * Newest-first recordings, optionally narrowed to one [status]. A `null` [status] means "all
     * statuses" and is the list-screen query.
     */
    suspend fun list(limit: Int = 50, status: TranscriptStatus? = null): List<Recording>

    suspend fun getById(id: Long): Recording?

    /**
     * Newest-first recordings attributed to one [personId] (the soft [Recording.personId] link).
     * Used by `person_brief` to reach the transcript moments of a person's recordings.
     */
    suspend fun recentForPerson(personId: Long, limit: Int = 20): List<Recording>

    /** Stores a recording (its [Recording.id] is ignored) and returns the new row id. */
    suspend fun insert(recording: Recording): Long

    /**
     * Moves a recording through the transcription lifecycle. [errorText] is kept only for
     * [TranscriptStatus.FAILED] and cleared for every other status.
     *
     * @return number of rows changed (`0` when the id is unknown).
     */
    suspend fun updateStatus(id: Long, status: TranscriptStatus, errorText: String? = null): Int

    /** Sets or clears the "keep regardless of retention" flag. */
    suspend fun setKeepFlag(id: Long, keepFlag: Boolean): Int

    suspend fun delete(id: Long): Int

    /** Recordings still waiting to be transcribed, oldest first. */
    suspend fun listPending(limit: Int = 50): List<Recording>

    suspend fun countByStatus(status: TranscriptStatus): Int
}
