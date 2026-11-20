package com.andharness.ru.data.index

import android.util.Log
import com.andharness.ru.domain.embedding.EmbeddingServer
import com.andharness.ru.domain.retrieval.VectorCodec
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One ranked hit of a whole-device semantic search: an indexed image or document chunk together with
 * its cosine [score] against the query embedding. [snippet] carries the matched document text (empty
 * for images, which have no text) and [sourceUri] is the MediaStore/SAF address the agent can open.
 */
data class DeviceSearchResult(
    val kind: DeviceIndexKind,
    val sourceUri: String,
    val displayName: String,
    val snippet: String,
    val score: Double,
    val mimeType: String,
    val chunkIndex: Int,
)

/**
 * Why a search returned no usable results. [OK] covers both a real hit list and a genuine empty
 * match is impossible to confuse with "the feature is not ready": [EMPTY_INDEX] and
 * [EMBEDDER_UNAVAILABLE] are honest capability states the tool surfaces to the agent.
 */
enum class DeviceSearchStatus { OK, NO_MATCH, EMPTY_INDEX, EMBEDDER_UNAVAILABLE }

/**
 * Honest outcome of [DeviceSearchService.searchDetailed]: the [status] plus the ranked [results].
 * [results] is empty whenever [status] is anything but [DeviceSearchStatus.OK].
 */
data class DeviceSearchOutcome(
    val status: DeviceSearchStatus,
    val results: List<DeviceSearchResult>,
)

/**
 * Pure, dependency-free ranking over the whole-device index. Kept separate from
 * [DeviceSearchService] so the cosine top-K is unit-testable on the JVM (no Android, no Room).
 *
 * The stored vectors are L2-normalised by [DeviceIndexRepository] on write
 * ([VectorCodec.normalize]); the query is normalised by the caller, so cosine similarity is just the
 * dot product. [TopK] is a bounded streaming accumulator: the service feeds it one batch at a time
 * so the whole index (potentially tens of thousands of 768-float BLOBs) is never held in memory at
 * once — only the best [TopK.limit] candidates are retained.
 *
 * `java.util.PriorityQueue` (a binary min-heap) is the bounded top-K structure; its head is the
 * smallest retained score, so a better candidate evicts it in O(log limit) —
 * https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/PriorityQueue.html
 */
object DeviceSearchRanker {

    /** One candidate to rank: an index row's identity plus its (L2-normalised) [vector]. */
    data class Candidate(
        val kind: DeviceIndexKind,
        val sourceUri: String,
        val displayName: String,
        val snippet: String,
        val mimeType: String,
        val chunkIndex: Int,
        val vector: FloatArray,
    )

    /**
     * Cosine similarity of two L2-normalised vectors, i.e. their dot product. Returns `0.0` — never
     * throws or returns NaN — for empty, mismatched-dimension or non-finite inputs, so one corrupt
     * row can never poison a scan.
     */
    fun cosine(query: FloatArray, vector: FloatArray): Double {
        if (query.isEmpty() || vector.isEmpty() || query.size != vector.size) return 0.0
        var dot = 0.0
        for (i in query.indices) {
            val score = query[i].toDouble() * vector[i].toDouble()
            if (!score.isFinite()) return 0.0
            dot += score
        }
        return if (dot.isFinite()) dot else 0.0
    }

    /**
     * Bounded top-K accumulator. Candidates whose cosine is not strictly positive (or not finite)
     * are dropped, so an unrelated query yields an empty list rather than arbitrary near-zero hits.
     */
    class TopK(
        private val query: FloatArray,
        limit: Int,
        private val minScore: Double = 0.0,
    ) {
        private val limit: Int = limit.coerceAtLeast(1)
        private val heap: PriorityQueue<Scored> = PriorityQueue(compareBy { it.score })

        private data class Scored(val score: Double, val candidate: Candidate)

        /** Scores and (maybe) retains [candidate]; keeps only the best [limit]. */
        fun offer(candidate: Candidate) {
            if (candidate.vector.isEmpty()) return
            val score = cosine(query, candidate.vector)
            if (!score.isFinite() || score <= minScore) return
            if (heap.size < limit) {
                heap.add(Scored(score, candidate))
            } else {
                val smallest = heap.peek()?.score ?: return
                if (smallest < score) {
                    heap.poll()
                    heap.add(Scored(score, candidate))
                }
            }
        }

        /** The retained candidates, best first; ties broken deterministically by source then chunk. */
        fun results(): List<DeviceSearchResult> = heap.toList()
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.candidate.sourceUri }
                    .thenBy { it.candidate.chunkIndex }
            )
            .map { scored -> scored.candidate.toResult(scored.score) }
    }

    /** Convenience over [TopK] for a fully-materialised candidate list (used by tests and callers). */
    fun topK(
        query: FloatArray,
        candidates: List<Candidate>,
        limit: Int,
        minScore: Double = 0.0,
    ): List<DeviceSearchResult> {
        val accumulator = TopK(query, limit, minScore)
        candidates.forEach(accumulator::offer)
        return accumulator.results()
    }

    private fun Candidate.toResult(score: Double): DeviceSearchResult = DeviceSearchResult(
        kind = kind,
        sourceUri = sourceUri,
        displayName = displayName,
        snippet = snippet,
        score = score,
        mimeType = mimeType,
        chunkIndex = chunkIndex,
    )
}

/**
 * Semantic search over the whole-device index `device_index_vectors` (images + document chunks),
 * callable from chat via the `device_search` tool. It embeds the query with the on-device
 * EmbeddingGemma 2 embedder ([EmbeddingServer.embedText], the SAME 768-dimensional text/image space
 * the index was built in) and ranks stored vectors by cosine similarity.
 *
 * Honest and bounded by design:
 * - nothing throws to the caller ([searchDetailed] reports a [DeviceSearchStatus]);
 * - the embedder not being downloaded/started → [DeviceSearchStatus.EMBEDDER_UNAVAILABLE];
 * - an empty index → [DeviceSearchStatus.EMPTY_INDEX]; no positive match → [DeviceSearchStatus.NO_MATCH];
 * - candidates are read in bounded batches ([CANDIDATE_BATCH]) up to [MAX_CANDIDATES], so large
 *   BLOBs are never all loaded at once.
 *
 * All work runs on [Dispatchers.IO] (Room suspend DAO + the embedder's HTTP call); nothing touches
 * the main thread — https://developer.android.com/training/data-storage/room/async-queries and
 * https://kotlinlang.org/docs/dispatchers.html
 */
@Singleton
class DeviceSearchService @Inject constructor(
    private val repository: DeviceIndexRepository,
    private val embedder: EmbeddingServer,
) {

    /**
     * Ranks up to [limit] index rows matching [query]; [kind] restricts to images or documents, or
     * `null` for both. Convenience over [searchDetailed] that drops the status and returns the hits
     * (empty on any honest short-circuit).
     */
    suspend fun search(
        query: String,
        kind: DeviceIndexKind?,
        limit: Int = DEFAULT_LIMIT,
    ): List<DeviceSearchResult> = searchDetailed(query, kind, limit).results

    /** Same as [search] but also returns WHY the result list is empty (capability state). */
    suspend fun searchDetailed(
        query: String,
        kind: DeviceIndexKind?,
        limit: Int = DEFAULT_LIMIT,
    ): DeviceSearchOutcome = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext DeviceSearchOutcome(DeviceSearchStatus.NO_MATCH, emptyList())
        }
        val indexSize = runCatching { repository.countAll() }.getOrDefault(0)
        if (indexSize <= 0) {
            return@withContext DeviceSearchOutcome(DeviceSearchStatus.EMPTY_INDEX, emptyList())
        }
        val queryVector = runCatching { embedder.embedText(trimmed) }.getOrNull()
        if (queryVector == null || queryVector.isEmpty()) {
            Log.i(TAG, "device_search: embedder unavailable; returning no results")
            return@withContext DeviceSearchOutcome(DeviceSearchStatus.EMBEDDER_UNAVAILABLE, emptyList())
        }

        val normalizedQuery = VectorCodec.normalize(queryVector)
        val topK = DeviceSearchRanker.TopK(normalizedQuery, limit.coerceIn(1, MAX_LIMIT))

        var offset = 0
        while (offset < MAX_CANDIDATES) {
            val batch = runCatching { repository.iterateBatch(CANDIDATE_BATCH, offset) }
                .getOrDefault(emptyList())
            if (batch.isEmpty()) break
            for (unit in batch) {
                if (kind != null && unit.kind != kind) continue
                topK.offer(unit.toCandidate())
            }
            offset += batch.size
            if (batch.size < CANDIDATE_BATCH) break
        }

        val results = topK.results()
        DeviceSearchOutcome(
            status = if (results.isEmpty()) DeviceSearchStatus.NO_MATCH else DeviceSearchStatus.OK,
            results = results,
        )
    }

    private fun DeviceIndexStoredUnit.toCandidate(): DeviceSearchRanker.Candidate =
        DeviceSearchRanker.Candidate(
            kind = kind,
            sourceUri = sourceUri,
            displayName = displayName,
            snippet = text,
            mimeType = mimeType,
            chunkIndex = chunkIndex,
            vector = vector,
        )

    private companion object {
        const val TAG = "Andharness"

        /** Rows fetched per Room read; bounds the BLOBs decoded at one time. */
        const val CANDIDATE_BATCH = 256

        /** Upper bound on rows scanned in one search (safety stop, independent of the index size). */
        const val MAX_CANDIDATES = 50_000

        /** Default hit count when the caller does not specify one. */
        const val DEFAULT_LIMIT = 20

        /** Hard cap on the returned hit count. */
        const val MAX_LIMIT = 100
    }
}
