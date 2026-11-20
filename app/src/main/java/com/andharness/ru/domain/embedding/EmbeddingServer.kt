package com.andharness.ru.domain.embedding

/**
 * Runs the on-device EmbeddingGemma 2 embedder as a second `libllamaserver.so` instance (separate
 * process and loopback port from the chat/local LLM server) and exposes text and image embeddings.
 *
 * The unified 768-dimensional space is shared by text and images, so a text query can retrieve a
 * photo (and vice versa) — the basis of the on-device `device_search` feature.
 *
 * All methods are failure-safe: any error is logged and reported as `null` (or `false`); nothing
 * throws and nothing runs on the main thread. A `null` embedding is an honest "could not embed",
 * never a silently-wrong zero vector.
 */
interface EmbeddingServer {

    /** OpenAI-compatible base URL, e.g. `http://127.0.0.1:8090/v1` (no trailing slash). */
    val baseUrl: String

    /** True while the embedder process is alive. */
    fun isRunning(): Boolean

    /**
     * Ensures the embedder process is up (idempotent, off the main thread) and returns whether it is
     * usable. Returns `false` — without throwing — when the model artifacts are not downloaded yet
     * or the process cannot start.
     */
    suspend fun ensureStarted(): Boolean

    /**
     * Embeds [text] into a [EmbedderModelSpec.EMBEDDING_DIM]-float vector, or `null` on any failure.
     * Starts the server on first use.
     */
    suspend fun embedText(text: String): FloatArray?

    /**
     * Embeds an image (raw bytes; e.g. PNG/JPEG) into the SAME vector space as [embedText], or `null`
     * on any failure. Starts the server on first use.
     */
    suspend fun embedImage(bytes: ByteArray): FloatArray?

    /** Stops the embedder process. Never deletes the downloaded model files. */
    fun stop()
}
