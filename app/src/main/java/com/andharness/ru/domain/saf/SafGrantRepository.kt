package com.andharness.ru.domain.saf

/**
 * Persistence for SAF tree grants. Implemented on top of the Room `saf_grants` table; the
 * schema change that introduced it must ship with an explicit migration (no destructive
 * fallback), see
 * https://developer.android.com/training/data-storage/room/migrating-db-versions
 */
interface SafGrantRepository {
    /** Stores a newly granted folder and returns its row id. */
    suspend fun add(uri: String, treeDocumentId: String, displayName: String): Long

    /** All grants, newest first. */
    suspend fun list(): List<SafGrant>

    suspend fun get(id: Long): SafGrant?

    /** Finds a grant by its persisted tree URI (unique). */
    suspend fun findByUri(uri: String): SafGrant?

    suspend fun remove(id: Long)

    /** Records the outcome of a grant re-validation, stamping the current time. */
    suspend fun markChecked(id: Long, ok: Boolean)
}
