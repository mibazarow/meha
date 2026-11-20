package com.andharness.ru.data.index

import com.andharness.ru.data.db.dao.DeviceIndexVectorDao
import com.andharness.ru.data.db.entity.DeviceIndexVectorEntity
import com.andharness.ru.domain.retrieval.VectorCodec
import javax.inject.Inject
import javax.inject.Singleton

/** One unit the indexer hands to [DeviceIndexRepository]: an image or one document text chunk. */
data class DeviceIndexUnit(
    val kind: DeviceIndexKind,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val chunkIndex: Int,
    val text: String,
    val vector: FloatArray,
    val modelId: String,
)

/**
 * One stored unit read back from the index: a [DeviceIndexUnit] plus its [id] and decoded [vector],
 * i.e. what the in-app cosine ranker consumes.
 */
data class DeviceIndexStoredUnit(
    val id: Long,
    val kind: DeviceIndexKind,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val chunkIndex: Int,
    val text: String,
    val vector: FloatArray,
    val dim: Int,
    val modelId: String,
    val updatedAt: Long,
)

/** File-level metadata of an indexed source, used by the incremental "what changed" scan. */
data class DeviceIndexSourceMeta(
    val lastModified: Long,
    val sizeBytes: Long,
)

/**
 * High-level access to the whole-device semantic index `device_index_vectors` for the indexer and the
 * searcher: it maps the typed [DeviceIndexUnit] to/from the Room row and applies
 * [VectorCodec] encoding, so callers never touch BLOBs or SQL.
 *
 * Every function is `suspend` on the Room `@Dao`: Room runs suspend queries on its own executor, off
 * the main thread, so nothing here blocks the UI
 * (https://developer.android.com/training/data-storage/room/async-queries). Vectors are stored
 * little-endian float32 via [VectorCodec] — the same encoding the message/memory stores use.
 */
@Singleton
class DeviceIndexRepository @Inject constructor(
    private val dao: DeviceIndexVectorDao,
) {

    /**
     * Inserts or replaces [units] (keyed by `sourceUri` + `chunkIndex`) and returns how many rows were
     * written. Units with a blank `sourceUri` or an empty vector are skipped. The vector is
     * L2-normalised defensively before storage (the embedder already normalises) so a plain dot
     * product ranks correctly.
     */
    suspend fun upsertUnits(
        units: List<DeviceIndexUnit>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val entities = units.mapNotNull { it.toEntity(now) }
        if (entities.isEmpty()) return 0
        dao.upsert(entities)
        return entities.size
    }

    /** Removes every indexed unit of [sourceUri] (called before re-indexing that file, or on delete). */
    suspend fun deleteSource(sourceUri: String) {
        if (sourceUri.isBlank()) return
        dao.deleteBySource(sourceUri)
    }

    /** Every indexed unit of [sourceUri], lowest chunk first — for incremental re-indexing. */
    suspend fun listBySource(sourceUri: String): List<DeviceIndexStoredUnit> =
        dao.listBySource(sourceUri).map { it.toStoredUnit() }

    /** File-level metadata of every indexed source, keyed by `sourceUri`. */
    suspend fun sourceMetadata(): Map<String, DeviceIndexSourceMeta> =
        dao.sourceMetadata().associate {
            it.sourceUri to DeviceIndexSourceMeta(lastModified = it.lastModified, sizeBytes = it.sizeBytes)
        }

    /**
     * File-level metadata of every indexed source of one [kind], keyed by `sourceUri`. The indexer
     * scopes its incremental scan to the kind it owns, so an image pass never treats a document source
     * as removed (and vice versa).
     */
    suspend fun sourceMetadataByKind(kind: DeviceIndexKind): Map<String, DeviceIndexSourceMeta> =
        dao.sourceMetadataByKind(kind.name).associate {
            it.sourceUri to DeviceIndexSourceMeta(lastModified = it.lastModified, sizeBytes = it.sizeBytes)
        }

    /** Number of indexed units of every kind. */
    suspend fun countAll(): Int = dao.countAll()

    /** Number of indexed units of one [kind]. */
    suspend fun countByKind(kind: DeviceIndexKind): Int = dao.countByKind(kind.name)

    /** Number of indexed units of one [kind] written by [modelId] (embedder-change detection). */
    suspend fun countByKindAndModel(kind: DeviceIndexKind, modelId: String): Int =
        dao.countByKindAndModel(kind.name, modelId)

    /** Drops every indexed unit of one [kind] (embedder change / index reset). */
    suspend fun deleteKind(kind: DeviceIndexKind) = dao.deleteByKind(kind.name)

    /** A bounded page of the index (stable by id) for in-app cosine ranking. */
    suspend fun iterateBatch(limit: Int, offset: Int): List<DeviceIndexStoredUnit> {
        if (limit <= 0) return emptyList()
        return dao.iterateBatch(limit, offset).map { it.toStoredUnit() }
    }

    /** Up to [limit] indexed units, lowest id first — a bounded candidate pool for ranking. */
    suspend fun getAll(limit: Int): List<DeviceIndexStoredUnit> {
        if (limit <= 0) return emptyList()
        return dao.getAll(limit).map { it.toStoredUnit() }
    }

    /** Deletes the entire index. */
    suspend fun clear() = dao.deleteAll()

    private fun DeviceIndexUnit.toEntity(now: Long): DeviceIndexVectorEntity? {
        if (sourceUri.isBlank() || vector.isEmpty()) return null
        val normalized = VectorCodec.normalize(vector)
        return DeviceIndexVectorEntity(
            kind = kind.name,
            sourceUri = sourceUri,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            chunkIndex = chunkIndex,
            text = text,
            vector = VectorCodec.encode(normalized),
            dim = normalized.size,
            modelId = modelId,
            updatedAt = now,
        )
    }

    private fun DeviceIndexVectorEntity.toStoredUnit(): DeviceIndexStoredUnit =
        DeviceIndexStoredUnit(
            id = id,
            // Unknown kind values (forward compatibility / corruption) fall back to DOC_CHUNK so a
            // single bad row cannot drop out of a scan entirely.
            kind = DeviceIndexKind.fromStorage(kind) ?: DeviceIndexKind.DOC_CHUNK,
            sourceUri = sourceUri,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            chunkIndex = chunkIndex,
            text = text,
            vector = VectorCodec.decode(vector),
            dim = dim,
            modelId = modelId,
            updatedAt = updatedAt,
        )
}
