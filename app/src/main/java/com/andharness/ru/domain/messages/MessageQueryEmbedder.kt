package com.andharness.ru.domain.messages

/**
 * Thin seam between the hybrid message search and the bundled on-device embedder.
 *
 * The search service only needs "embed this query to a unit vector, or `null` when semantics are
 * unavailable"; keeping that behind a one-method interface lets the service be unit-tested on the
 * JVM with a fake, while the production binding wraps [com.andharness.ru.data.embedding.LocalEmbedder]
 * and the `semanticSearchEnabled` switch (see
 * `com.andharness.ru.data.messages.LocalMessageQueryEmbedder`). Implementations must never throw.
 */
fun interface MessageQueryEmbedder {
    /** Returns the query embedding, or `null` when semantics are off/unavailable or the text is blank. */
    suspend fun embed(text: String): FloatArray?
}
