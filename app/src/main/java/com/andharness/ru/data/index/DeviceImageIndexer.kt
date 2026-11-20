package com.andharness.ru.data.index

import android.util.Log
import com.andharness.ru.data.embedding.EmbedderDownloadStore
import com.andharness.ru.domain.embedding.EmbedderModelSpec
import com.andharness.ru.domain.embedding.EmbeddingServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Outcome of one indexing pass. `permissionDenied` and `modelReady = false` are honest short-circuits:
 * the pass did nothing and the caller should retry later once the permission/model is available.
 */
data class DeviceImageIndexResult(
    val indexed: Int,
    val updated: Int,
    val deleted: Int,
    val skipped: Int,
    val failed: Int,
    val total: Int,
    val permissionDenied: Boolean,
    val modelReady: Boolean,
    val wasFullScan: Boolean,
    val truncated: Boolean,
)

/**
 * Indexes the device's gallery images into the whole-device semantic index as [DeviceIndexKind.IMAGE]
 * units (one row per image, `chunkIndex = 0`, empty text) using EmbeddingGemma 2 image embeddings.
 *
 * Flow (one pass, [index]):
 * 1. Skip cleanly when the embedder artifacts are not downloaded ([EmbedderDownloadStore.isReady]) or
 *    the media-read permission is missing.
 * 2. Enumerate images with a bounded MediaStore query ([MediaStoreImageSource]).
 * 3. Detect an embedder change via `modelId` ([EmbedderModelSpec.MODEL_ID]); on a change (or an empty
 *    index) drop the stale IMAGE rows and run a FULL scan.
 * 4. Compute the minimal incremental plan ([DeviceIndexDiff]) and delete rows for images gone from the
 *    device (skipped when the scan was truncated, so a partial enumeration can never mis-delete).
 * 5. Embed only the new/changed images, one thumbnail at a time, in small batches.
 *
 * Safety/performance:
 * - Every failure is caught; nothing throws, and nothing runs on the main thread (`Dispatchers.IO`).
 * - Images are decoded downscaled ([ImageThumbnailDecoder]); full-size bitmaps are never held.
 * - A per-process [Mutex] serialises passes so a periodic run and a realtime run never race; the DB
 *   writes are idempotent upserts keyed by (`sourceUri`, `chunkIndex`).
 * - Counts of indexed/updated/deleted/skipped/failed are returned and logged.
 */
@Singleton
class DeviceImageIndexer @Inject constructor(
    private val repository: DeviceIndexRepository,
    private val source: MediaStoreImageSource,
    private val thumbnails: ImageThumbnailDecoder,
    private val embedder: EmbeddingServer,
    private val embedderStore: EmbedderDownloadStore,
    private val indexLock: DeviceIndexLock,
) {

    /**
     * Runs ONE pass. [progress] is invoked (off the main thread) with `done`/`total` of the images to
     * embed so a foreground notification can be kept current; it defaults to a no-op.
     *
     * The process-wide [DeviceIndexLock] serialises this pass with the document indexer so the two
     * heavy passes never run at once.
     */
    suspend fun index(
        progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DeviceImageIndexResult = indexLock.mutex.withLock {
        withContext(Dispatchers.IO) { runPass(progress) }
    }

    private suspend fun runPass(
        progress: suspend (done: Int, total: Int) -> Unit,
    ): DeviceImageIndexResult {
        if (!embedderStore.isReady()) {
            Log.i(TAG, "Device image index skipped: embedder artifacts are not ready")
            return empty(modelReady = false)
        }
        if (!source.hasPermission()) {
            Log.i(TAG, "Device image index skipped: media-read permission not granted")
            return empty(permissionDenied = true)
        }

        val scan = source.query(MAX_IMAGES)
        val images = when (scan) {
            is DeviceImageScan.PermissionDenied -> {
                Log.i(TAG, "Device image index skipped: permission denied at query time")
                return empty(permissionDenied = true)
            }

            is DeviceImageScan.Failed -> {
                Log.w(TAG, "Device image index skipped: ${scan.reason}")
                return empty()
            }

            is DeviceImageScan.Success -> scan.images
        }

        // Embedder change (or empty index) => the stored vectors are stale => full re-index.
        val knownBefore = repository.sourceMetadataByKind(DeviceIndexKind.IMAGE)
        val modelChanged = isModelChanged()
        val wasFullScan = modelChanged || (knownBefore.isEmpty() && images.isNotEmpty())
        if (modelChanged) {
            Log.i(TAG, "Embedder changed: dropping ${knownBefore.size} stale image source(s) for re-index")
            repository.deleteKind(DeviceIndexKind.IMAGE)
        }
        val known = if (modelChanged) emptyMap() else knownBefore

        val plan = DeviceIndexDiff.plan(
            known = known,
            current = images.map {
                DeviceIndexDiff.Candidate(
                    sourceUri = it.sourceUri,
                    lastModified = it.lastModified,
                    sizeBytes = it.sizeBytes,
                )
            },
        )

        // Deletion is only trustworthy on a COMPLETE enumeration; a truncated scan must not drop rows.
        var deleted = 0
        if (!scan.truncated) {
            for (uri in plan.toDelete) {
                runCatching { repository.deleteSource(uri) }
                    .onSuccess { deleted++ }
                    .onFailure { Log.w(TAG, "Failed to delete stale image row for $uri", it) }
            }
        }

        val byUri = images.associateBy { it.sourceUri }
        val addSet = plan.toAdd.toHashSet()
        val targets = plan.toIndex.mapNotNull { byUri[it] }
        val total = targets.size

        var indexed = 0
        var updated = 0
        var failed = 0
        var done = 0
        progress(0, total)
        for (batch in targets.chunked(EMBED_BATCH_SIZE)) {
            val units = ArrayList<DeviceIndexUnit>(batch.size)
            for (entry in batch) {
                val bytes = runCatching { thumbnails.decodeJpeg(entry.sourceUri) }.getOrNull()
                if (bytes == null) {
                    failed++
                    done++
                    continue
                }
                val vector = runCatching { embedder.embedImage(bytes) }.getOrNull()
                if (vector == null || vector.isEmpty()) {
                    failed++
                    done++
                    continue
                }
                units += DeviceIndexUnit(
                    kind = DeviceIndexKind.IMAGE,
                    sourceUri = entry.sourceUri,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    lastModified = entry.lastModified,
                    chunkIndex = 0,
                    text = "",
                    vector = vector,
                    modelId = EmbedderModelSpec.MODEL_ID,
                )
                done++
            }
            if (units.isNotEmpty()) {
                val written = runCatching { repository.upsertUnits(units) }
                    .onFailure { Log.w(TAG, "Failed to write ${units.size} image vector(s)", it) }
                    .getOrDefault(0)
                for (unit in units.take(written)) {
                    if (unit.sourceUri in addSet) indexed++ else updated++
                }
            }
            progress(done, total)
        }

        val result = DeviceImageIndexResult(
            indexed = indexed,
            updated = updated,
            deleted = deleted,
            skipped = plan.unchanged,
            failed = failed,
            total = images.size,
            permissionDenied = false,
            modelReady = true,
            wasFullScan = wasFullScan,
            truncated = scan.truncated,
        )
        Log.i(
            TAG,
            "Device image index done: indexed=$indexed updated=$updated deleted=$deleted " +
                "skipped=${result.skipped} failed=$failed scanned=${result.total} full=$wasFullScan " +
                "truncated=${scan.truncated}",
        )
        return result
    }

    /** True when some stored IMAGE rows were written by a different embedder than the current one. */
    private suspend fun isModelChanged(): Boolean {
        val all = runCatching { repository.countByKind(DeviceIndexKind.IMAGE) }.getOrDefault(0)
        if (all <= 0) return false
        val current = runCatching {
            repository.countByKindAndModel(DeviceIndexKind.IMAGE, EmbedderModelSpec.MODEL_ID)
        }.getOrDefault(0)
        return current < all
    }

    private fun empty(
        permissionDenied: Boolean = false,
        modelReady: Boolean = true,
    ) = DeviceImageIndexResult(
        indexed = 0,
        updated = 0,
        deleted = 0,
        skipped = 0,
        failed = 0,
        total = 0,
        permissionDenied = permissionDenied,
        modelReady = modelReady,
        wasFullScan = false,
        truncated = false,
    )

    companion object {
        private const val TAG = "Andharness"

        /**
         * Hard upper bound on images enumerated in ONE pass. File-level rows are tiny, so this is a
         * safety stop rather than a real limit; when it is hit the pass reports `truncated` and skips
         * deletion, and later passes index the rest (adds only).
         */
        const val MAX_IMAGES = 50_000

        /** Images embedded per DB batch; bounds one on-device model run (matches the chat indexer). */
        const val EMBED_BATCH_SIZE = 8
    }
}
