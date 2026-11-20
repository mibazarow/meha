package com.andharness.ru.domain.communications

/**
 * Persistence for transcript segments and their search indexes (tables `transcript_segments`,
 * `transcript_fts`, `transcript_vectors`).
 *
 * The lexical index is queried with a free-text [searchFts] query that the repository sanitises into
 * a safe FTS4 `MATCH` expression; the dense index is keyed by segment id so one embedding per
 * segment is stored.
 */
interface TranscriptRepository {

    /** Segments of one recording in playback order. */
    suspend fun segmentsFor(recordingId: Long): List<TranscriptSegment>

    /**
     * Full segments for the given [ids] (e.g. the candidates [searchFts] returned). Order is not
     * guaranteed; an empty [ids] list yields an empty result without touching the index.
     */
    suspend fun segmentsByIds(ids: List<Long>): List<TranscriptSegment>

    /**
     * Inserts [segments] under [recordingId] (each segment's own id/recordingId is ignored) and
     * returns the new row ids in input order.
     */
    suspend fun insertSegments(recordingId: Long, segments: List<TranscriptSegment>): List<Long>

    suspend fun deleteFor(recordingId: Long): Int

    /**
     * Assigns (or clears, with `null`) the diarised speaker of one segment.
     *
     * @return number of rows changed (`0` when the segment id is unknown).
     */
    suspend fun assignSpeaker(segmentId: Long, speakerId: Long?): Int

    /**
     * Candidate segment ids whose text matches [query], via the FTS4 index. A blank/no-term query
     * yields an empty list (the caller falls back to a full scan if it wants one).
     */
    suspend fun searchFts(query: String, limit: Int = 20): List<Long>

    /** Stores (or replaces) the dense embedding of one segment. */
    suspend fun putVector(segmentId: Long, vector: FloatArray, model: String)

    /** The stored embedding of one segment, or `null` when none/undecodable. */
    suspend fun vector(segmentId: Long): FloatArray?

    /**
     * The newest stored segment embeddings, at most [limit] (newest segment id first), skipping
     * rows that are missing a model match or whose bytes are undecodable. This is the BOUNDED
     * candidate pool for semantic transcript search, so the scan can never read the whole store.
     */
    suspend fun recentVectors(limit: Int): List<Pair<Long, FloatArray>>

    /** Segment ids that have no embedding row yet, oldest first. */
    suspend fun missingVectorSegmentIds(limit: Int = 50): List<Long>
}
