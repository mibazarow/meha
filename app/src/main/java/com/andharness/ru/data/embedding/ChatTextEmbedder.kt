package com.andharness.ru.data.embedding

/**
 * The one operation the chat-message indexer needs from an on-device embedder: encode a list of
 * texts (order-preserving) to vectors. Extracted as a tiny interface so the indexer's
 * batching/upsert/skip logic can be exercised by pure JVM tests with a fake, without loading the
 * ~29 MB ONNX session that the production [LocalEmbedder] owns.
 *
 * [LocalEmbedder] is the only production implementation.
 */
interface ChatTextEmbedder {

    /**
     * Encodes [texts] (order-preserving) to vectors. Empty input is an empty success; ordinary
     * failures are returned as [Result.failure] rather than thrown.
     */
    suspend fun embed(texts: List<String>): Result<List<FloatArray>>
}
