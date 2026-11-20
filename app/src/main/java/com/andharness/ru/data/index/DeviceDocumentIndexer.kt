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
 * Outcome of one document indexing pass. `permissionDenied` and `modelReady = false` are honest
 * short-circuits: the pass did nothing and the caller should retry later once the permission/model is
 * available. `indexed`/`updated` count index rows (chunks) written; `documentsIndexed` counts the
 * distinct documents that produced at least one chunk; `ocrDocuments` counts those whose text came
 * (at least partly) from PDF OCR. `unsupported` counts documents that were honestly *skipped* because
 * they carry nothing to index (an unsupported format, no extractable text, or an oversized file),
 * while `failed` counts genuine extraction errors — the two are reported separately so a bad file is
 * never hidden behind a bare "failed" count (each is logged with its reason by [DocumentTextSource]).
 */
data class DeviceDocumentIndexResult(
    val indexed: Int,
    val updated: Int,
    val deleted: Int,
    val skipped: Int,
    val failed: Int,
    val unsupported: Int,
    val documentsIndexed: Int,
    val ocrDocuments: Int,
    val total: Int,
    val permissionDenied: Boolean,
    val modelReady: Boolean,
    val wasFullScan: Boolean,
    val truncated: Boolean,
)

/**
 * Indexes the device's documents into the whole-device semantic index as [DeviceIndexKind.DOC_CHUNK]
 * units — one row per text chunk of a document — using EmbeddingGemma 2 text embeddings.
 *
 * Flow (one pass, [index]):
 * 1. Skip cleanly when the embedder artifacts are not downloaded ([EmbedderDownloadStore.isReady]) or
 *    shared-storage document access is missing ([MediaStoreDocumentSource.hasPermission]).
 * 2. Enumerate documents with a bounded `MediaStore.Files` query ([MediaStoreDocumentSource]).
 * 3. Detect an embedder change via `modelId` ([EmbedderModelSpec.MODEL_ID]); on a change (or an empty
 *    index) drop the stale DOC_CHUNK rows and run a FULL scan.
 * 4. Compute the minimal incremental plan ([DeviceIndexDiff.plan]) and delete rows for documents
 *    gone from the device (skipped when the scan was truncated, so a partial enumeration can never
 *    mis-delete).
 * 5. For each new/changed document: extract its text ([DocumentTextSource]), split it into
 *    ~400–500-token chunks ([DocumentChunker]), embed each chunk and upsert its DOC_CHUNK row. The
 *    document's previous rows are cleared first so a shorter new version leaves no stale chunks.
 *
 * Safety/performance:
 * - Every failure is caught; nothing throws, and nothing runs on the main thread (`Dispatchers.IO`).
 * - Documents are staged one at a time and their text is capped; PDF OCR is bounded to a few pages.
 * - The process-wide [DeviceIndexLock] serialises passes with the image indexer; the DB writes are
 *    idempotent upserts keyed by (`sourceUri`, `chunkIndex`).
 * - Counts and progress are returned/logged; a pass stops after [MAX_EMBEDS_PER_PASS] chunk
 *    embeddings and the rest is picked up on the next pass.
 */
@Singleton
class DeviceDocumentIndexer @Inject constructor(
    private val repository: DeviceIndexRepository,
    private val source: MediaStoreDocumentSource,
    private val textSource: DocumentTextSource,
    private val embedder: EmbeddingServer,
    private val embedderStore: EmbedderDownloadStore,
    private val indexLock: DeviceIndexLock,
) {

    /**
     * Runs ONE pass. [progress] is invoked (off the main thread) with `done`/`total` of the documents
     * to process so a foreground notification can be kept current; it defaults to a no-op.
     *
     * The process-wide [DeviceIndexLock] serialises this pass with the image indexer so the two heavy
     * passes never run at once.
     */
    suspend fun index(
        progress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): DeviceDocumentIndexResult = indexLock.mutex.withLock {
        withContext(Dispatchers.IO) { runPass(progress) }
    }

    private suspend fun runPass(
        progress: suspend (done: Int, total: Int) -> Unit,
    ): DeviceDocumentIndexResult {
        if (!embedderStore.isReady()) {
            Log.i(TAG, "Device document index skipped: embedder artifacts are not ready")
            return empty(modelReady = false)
        }
        if (!source.hasPermission()) {
            Log.i(TAG, "Device document index skipped: shared-storage document access not granted")
            return empty(permissionDenied = true)
        }

        val scan = source.query(MAX_DOCUMENTS)
        val documents = when (scan) {
            is DeviceDocumentScan.PermissionDenied -> {
                Log.i(TAG, "Device document index skipped: permission denied at query time")
                return empty(permissionDenied = true)
            }

            is DeviceDocumentScan.Failed -> {
                Log.w(TAG, "Device document index skipped: ${scan.reason}")
                return empty()
            }

            is DeviceDocumentScan.Success -> scan.documents
        }

        // Embedder change (or empty index) => the stored vectors are stale => full re-index.
        val knownBefore = repository.sourceMetadataByKind(DeviceIndexKind.DOC_CHUNK)
        val modelChanged = isModelChanged()
        val wasFullScan = modelChanged || (knownBefore.isEmpty() && documents.isNotEmpty())
        if (modelChanged) {
            Log.i(TAG, "Embedder changed: dropping ${knownBefore.size} stale document source(s)")
            repository.deleteKind(DeviceIndexKind.DOC_CHUNK)
        }
        val known = if (modelChanged) emptyMap() else knownBefore

        val plan = DeviceIndexDiff.plan(
            known = known,
            current = documents.map {
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
                    .onFailure { Log.w(TAG, "Failed to delete stale document rows for $uri", it) }
            }
        }

        val byUri = documents.associateBy { it.sourceUri }
        val addSet = plan.toAdd.toHashSet()
        val targets = plan.toIndex.mapNotNull { byUri[it] }
        val total = targets.size

        var indexed = 0
        var updated = 0
        var failed = 0
        var unsupported = 0
        var documentsIndexed = 0
        var ocrDocuments = 0
        var embeddings = 0
        var done = 0
        progress(0, total)
        for (entry in targets) {
            if (embeddings >= MAX_EMBEDS_PER_PASS) {
                Log.i(TAG, "Stopping document index pass at $MAX_EMBEDS_PER_PASS embeddings; rest next pass")
                break
            }
            // [DocumentTextSource.extract] is failure-safe per file and classifies skip vs fail, but
            // keep a last-resort guard so a single odd document can never abort the whole pass.
            val outcome = runCatching { textSource.extract(entry) }
                .onFailure { Log.w(TAG, "Document extraction crashed for ${entry.displayName}", it) }
                .getOrNull()
            done++
            if (outcome !is DocumentExtraction.Text) {
                // Null (guard tripped) is a genuine failure; a classified non-Text is a skip or fail.
                if (outcome is DocumentExtraction.Skipped) unsupported++ else failed++
                progress(done, total)
                continue
            }
            val extracted = outcome
            val chunks = DocumentChunker.chunk(extracted.text).take(MAX_CHUNKS_PER_DOC)
            if (chunks.isEmpty()) {
                failed++
                progress(done, total)
                continue
            }
            documentsIndexed++
            if (extracted.ocrUsed) ocrDocuments++

            val units = ArrayList<DeviceIndexUnit>(chunks.size)
            chunks.forEachIndexed { chunkIndex, chunk ->
                val vector = runCatching { embedder.embedText(chunk) }.getOrNull()
                if (vector == null || vector.isEmpty()) return@forEachIndexed
                units += DeviceIndexUnit(
                    kind = DeviceIndexKind.DOC_CHUNK,
                    sourceUri = entry.sourceUri,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    lastModified = entry.lastModified,
                    chunkIndex = chunkIndex,
                    text = chunk,
                    vector = vector,
                    modelId = EmbedderModelSpec.MODEL_ID,
                )
                embeddings++
            }
            if (units.isNotEmpty()) {
                // Clear the document's previous chunks first so a shorter new version leaves none.
                runCatching { repository.deleteSource(entry.sourceUri) }
                    .onFailure { Log.w(TAG, "Failed to clear old chunks for ${entry.sourceUri}", it) }
                val written = runCatching { repository.upsertUnits(units) }
                    .onFailure { Log.w(TAG, "Failed to write ${units.size} document chunk(s)", it) }
                    .getOrDefault(0)
                val isNew = entry.sourceUri in addSet
                repeat(minOf(units.size, written)) {
                    if (isNew) indexed++ else updated++
                }
            }
            progress(done, total)
        }

        val result = DeviceDocumentIndexResult(
            indexed = indexed,
            updated = updated,
            deleted = deleted,
            skipped = plan.unchanged,
            failed = failed,
            unsupported = unsupported,
            documentsIndexed = documentsIndexed,
            ocrDocuments = ocrDocuments,
            total = documents.size,
            permissionDenied = false,
            modelReady = true,
            wasFullScan = wasFullScan,
            truncated = scan.truncated,
        )
        Log.i(
            TAG,
            "Device document index done: indexed=$indexed updated=$updated deleted=$deleted " +
                "skipped=${result.skipped} failed=$failed unsupported=$unsupported " +
                "docs=$documentsIndexed ocr=$ocrDocuments " +
                "scanned=${result.total} full=$wasFullScan truncated=${scan.truncated}",
        )
        return result
    }

    /** True when some stored DOC_CHUNK rows were written by a different embedder than the current. */
    private suspend fun isModelChanged(): Boolean {
        val all = runCatching { repository.countByKind(DeviceIndexKind.DOC_CHUNK) }.getOrDefault(0)
        if (all <= 0) return false
        val current = runCatching {
            repository.countByKindAndModel(DeviceIndexKind.DOC_CHUNK, EmbedderModelSpec.MODEL_ID)
        }.getOrDefault(0)
        return current < all
    }

    private fun empty(
        permissionDenied: Boolean = false,
        modelReady: Boolean = true,
    ) = DeviceDocumentIndexResult(
        indexed = 0,
        updated = 0,
        deleted = 0,
        skipped = 0,
        failed = 0,
        unsupported = 0,
        documentsIndexed = 0,
        ocrDocuments = 0,
        total = 0,
        permissionDenied = permissionDenied,
        modelReady = modelReady,
        wasFullScan = false,
        truncated = false,
    )

    companion object {
        private const val TAG = "Andharness"

        /**
         * Hard upper bound on documents enumerated in ONE pass. File-level rows are tiny, so this is a
         * safety stop rather than a real limit; when it is hit the pass reports `truncated` and skips
         * deletion, and later passes index the rest (adds only).
         */
        const val MAX_DOCUMENTS = 20_000

        /** Chunks embedded per document; bounds one very long document's cost. */
        const val MAX_CHUNKS_PER_DOC = 60

        /** Upper bound on chunk embeddings in ONE pass; the rest is picked up on the next pass. */
        const val MAX_EMBEDS_PER_PASS = 1_500
    }
}
