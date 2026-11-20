package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.andharness.ru.data.db.entity.DeviceIndexVectorEntity

/**
 * DAO for the whole-device semantic index `device_index_vectors`.
 *
 * Writes are chunk-scoped: [upsert] uses the unique (`sourceUri`, `chunkIndex`) key with REPLACE, so
 * re-indexing a file overwrites exactly the chunks it re-produces and is idempotent. Reads are
 * bounded ([iterateBatch] / [getAll]) because the in-app cosine ranking loads a candidate window
 * rather than the whole index at once.
 *
 * [sourceMetadata] is a column-subset projection (a data class returned from a `@Query`) the
 * incremental indexer uses to decide what changed; it returns file-level metadata only, never the
 * vectors. `@Insert(onConflict = OnConflictStrategy.REPLACE)` is the documented insert conflict
 * strategy and Room validates every query at compile time
 * (https://developer.android.com/training/data-storage/room/accessing-data).
 */
@Dao
interface DeviceIndexVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<DeviceIndexVectorEntity>)

    /** Removes every indexed chunk of one file (used before re-indexing it). */
    @Query("DELETE FROM device_index_vectors WHERE sourceUri = :sourceUri")
    suspend fun deleteBySource(sourceUri: String)

    /** Every indexed chunk of one file, lowest chunk first — for incremental re-indexing. */
    @Query("SELECT * FROM device_index_vectors WHERE sourceUri = :sourceUri ORDER BY chunkIndex ASC")
    suspend fun listBySource(sourceUri: String): List<DeviceIndexVectorEntity>

    /** Number of indexed units (all kinds). */
    @Query("SELECT COUNT(*) FROM device_index_vectors")
    suspend fun countAll(): Int

    /** Number of indexed units of one `kind` (`IMAGE` / `DOC_CHUNK`). */
    @Query("SELECT COUNT(*) FROM device_index_vectors WHERE kind = :kind")
    suspend fun countByKind(kind: String): Int

    /**
     * Number of indexed units of one `kind` written by the [modelId] embedder. Compared against
     * [countByKind] by the indexer to detect an embedder change (fewer current-model rows than rows =
     * stale vectors from a previous model) and trigger a full re-index.
     */
    @Query("SELECT COUNT(*) FROM device_index_vectors WHERE kind = :kind AND modelId = :modelId")
    suspend fun countByKindAndModel(kind: String, modelId: String): Int

    /** Drops every indexed unit of one `kind` (used on an embedder change / index reset). */
    @Query("DELETE FROM device_index_vectors WHERE kind = :kind")
    suspend fun deleteByKind(kind: String)

    /**
     * A bounded page of the index for in-app cosine ranking, ordered by [id] so paging is stable. The
     * embedded `vector` BLOB is returned; `text` is included for result snippets.
     */
    @Query("SELECT * FROM device_index_vectors ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun iterateBatch(limit: Int, offset: Int): List<DeviceIndexVectorEntity>

    /** Up to [limit] indexed units, lowest id first — a bounded candidate pool for ranking. */
    @Query("SELECT * FROM device_index_vectors ORDER BY id ASC LIMIT :limit")
    suspend fun getAll(limit: Int): List<DeviceIndexVectorEntity>

    /** File-level metadata of every indexed source, for the incremental "what changed" scan. */
    @Query(
        "SELECT sourceUri, MAX(lastModified) AS lastModified, MAX(sizeBytes) AS sizeBytes " +
            "FROM device_index_vectors GROUP BY sourceUri"
    )
    suspend fun sourceMetadata(): List<DeviceIndexSourceRow>

    /**
     * File-level metadata of every indexed source OF ONE [kind], for the incremental scan. Scoping by
     * `kind` is required so the image pass and the (future) document pass never see each other's
     * sources and mis-delete them as "no longer present".
     */
    @Query(
        "SELECT sourceUri, MAX(lastModified) AS lastModified, MAX(sizeBytes) AS sizeBytes " +
            "FROM device_index_vectors WHERE kind = :kind GROUP BY sourceUri"
    )
    suspend fun sourceMetadataByKind(kind: String): List<DeviceIndexSourceRow>

    /** Deletes the entire index (settings "clear index" / embedder change). */
    @Query("DELETE FROM device_index_vectors")
    suspend fun deleteAll()
}

/** File-level projection of [DeviceIndexVectorDao.sourceMetadata]; column names match the query. */
data class DeviceIndexSourceRow(
    val sourceUri: String,
    val lastModified: Long,
    val sizeBytes: Long
)
