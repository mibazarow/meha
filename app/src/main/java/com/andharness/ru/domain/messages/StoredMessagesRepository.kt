package com.andharness.ru.domain.messages

/**
 * Persistence for captured messages and their search indexes (tables `stored_messages`,
 * `stored_message_fts`, `stored_message_vectors`).
 *
 * The lexical index is queried with a free-text [searchFts] query that the repository sanitises into a
 * safe FTS4 `MATCH` expression; the dense index is keyed by message id so one embedding per message is
 * stored. [externalRef] is unique when non-null, so [findByExternalRef] is the dedup guard ingest can
 * use before [insert].
 */
interface StoredMessagesRepository {

    /** Inserts [message] (its own id is ignored) and returns the new row id. */
    suspend fun insert(message: StoredMessage): Long

    /**
     * Newest messages first, optionally restricted to one [sourceType] and/or one [personId]; both
     * filters are `null`-able and `null` means "no restriction".
     */
    suspend fun recent(limit: Int, sourceType: String? = null, personId: Long? = null): List<StoredMessage>

    /** Messages of one conversation in chronological order. */
    suspend fun forConversation(key: String, limit: Int = 50): List<StoredMessage>

    /** Full messages for the given [ids] (e.g. FTS candidates or ranked semantic hits). */
    suspend fun byIds(ids: List<Long>): List<StoredMessage>

    /**
     * Messages whose text matches [query], via the FTS4 index. A blank/no-term query yields an empty
     * list (the caller falls back to a full scan if it wants one).
     */
    suspend fun searchFts(query: String, limit: Int = 20): List<StoredMessage>

    /** Stores (or replaces) the dense embedding of one message. */
    suspend fun putVector(messageId: Long, vector: FloatArray, model: String)

    /** Message ids that have no embedding row yet, oldest first. */
    suspend fun missingVectorIds(limit: Int = 50): List<Long>

    /** The stored embedding of one message, or `null` when none/undecodable. */
    suspend fun vector(messageId: Long): FloatArray?

    /**
     * The newest already-embedded messages, at most [limit], as `(messageId, vector)` pairs — the
     * bounded candidate pool for semantic message search. Vectors of a different embedding model are
     * skipped so a stale row is never scored.
     */
    suspend fun recentVectors(limit: Int): List<Pair<Long, FloatArray>>

    /** Deletes every message (and, via the FTS triggers, its index rows) of one person. */
    suspend fun deleteForPerson(personId: Long): Int

    /** The message previously stored for [ref], or `null`; the ingest dedup guard. */
    suspend fun findByExternalRef(ref: String): StoredMessage?
}
