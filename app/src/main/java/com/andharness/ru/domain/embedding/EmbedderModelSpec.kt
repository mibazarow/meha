package com.andharness.ru.domain.embedding

import com.andharness.ru.domain.downloads.ModelDownloadPolicy
import com.andharness.ru.domain.localserver.FreeSpaceDecision

/**
 * The EmbeddingGemma 2 embedder artifacts the app downloads for on-device semantic search over the
 * whole device (files, documents, photos): the text/vision model and its multimodal projector
 * (mmproj) companion.
 *
 * Weights are downloaded from the upstream Hugging Face repository
 * `ggml-org/embeddinggemma-2-GGUF` (`[ModelFile.url]`) and are NEVER bundled in the APK. Both files
 * are required: the embedder and the mmproj that enables image embeddings.
 *
 * There is no longer a project-owned mirror: the app has no update/download server of its own. A
 * `fallbackUrl` is kept in the model for a future GitHub Releases mirror of the SAME artifact
 * (identical file name, size and SHA-256); it is `null` today, and
 * [com.andharness.ru.domain.embedding.EmbedderSourceSelector] simply tries the single primary source.
 * Whichever source is used, the finished file is verified against the single [ModelFile.sha256].
 *
 * Sizes are the exact served `content-length`s and `sha256` is the SHA-256 of the exact served file,
 * so a finished download can be verified byte-for-byte. Both the primary server and the Hugging Face
 * CDN support HTTP `Range` requests, so resumable partial downloads work.
 *
 * Mirrors the shared download arithmetic in [ModelDownloadPolicy]. Deliberately pure JVM (no Android
 * imports) so it is unit-testable.
 */
object EmbedderModelSpec {

    /** Human-readable model label shown in the UI. */
    const val NAME = "EmbeddingGemma 2 (Q8_0)"

    /**
     * Stable id of the embedder, written onto every indexed row (`device_index_vectors.modelId`). The
     * indexer compares the stored id with this value to detect an embedder change and re-embed, so the
     * id must change whenever the produced vectors would change.
     */
    const val MODEL_ID = "embeddinggemma-2-q8_0"

    /**
     * SHA-256 of the `mmproj-embeddinggemma-2-Q8_0.gguf` file served by
     * `ggml-org/embeddinggemma-2-GGUF` — the same upstream repository the weights come from.
     */
    private const val MMPROJ_SHA256 = "c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f"

    /**
     * Output dimensionality of the unified text/image embedding space (768, per the model card:
     * https://huggingface.co/google/embeddinggemma-2 — "Native Output Dimension 768").
     */
    const val EMBEDDING_DIM = 768

    /**
     * The serving chain is `libllamaserver.so` (llama.cpp), which emits `data[0].embedding` of
     * exactly [EMBEDDING_DIM] floats for `/v1/embeddings` with `--pooling mean`.
     */

    /**
     * One downloadable artifact: where it lives, where it comes from (a primary URL and an optional
     * fallback), and how to verify it.
     *
     * [url] is the upstream Hugging Face copy (primary); [fallbackUrl] is an optional mirror of the
     * SAME file/size/SHA-256, tried only when the primary fails (see
     * [com.andharness.ru.domain.embedding.EmbedderSourceSelector]). `null` when the artifact has no
     * fallback. Both sources must serve byte-identical artifacts, so either way the finished file is
     * verified against the single [sha256].
     */
    data class ModelFile(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        val fallbackUrl: String? = null,
    )

    /** Base URL of the upstream Hugging Face copy that serves both artifacts. */
    const val HF_BASE_URL = "https://huggingface.co/ggml-org/embeddinggemma-2-GGUF/resolve/main/"

    /** The EmbeddingGemma 2 embedder weights (Q8_0), text + vision backbone. */
    val MODEL: ModelFile = ModelFile(
        fileName = "embeddinggemma-2-Q8_0.gguf",
        url = "${HF_BASE_URL}embeddinggemma-2-Q8_0.gguf?download=true",
        sizeBytes = 309_855_456L,
        sha256 = "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135",
        fallbackUrl = null,
    )

    /** The multimodal projector (Q8_0) that enables image input; required alongside [MODEL]. */
    val MMPROJ: ModelFile = ModelFile(
        fileName = "mmproj-embeddinggemma-2-Q8_0.gguf",
        url = "${HF_BASE_URL}mmproj-embeddinggemma-2-Q8_0.gguf?download=true",
        sizeBytes = 554_821_024L,
        sha256 = MMPROJ_SHA256,
        fallbackUrl = null,
    )

    /** Every required file, downloaded in [FILES] order. */
    val FILES: List<ModelFile> = listOf(MODEL, MMPROJ)

    /** Total bytes of all required files (weights + mmproj). */
    val TOTAL_BYTES: Long = FILES.sumOf { it.sizeBytes }

    // ---- Single-file aliases (weights only) ------------------------------------------------

    /** Base name of the primary (weights) file inside `<filesDir>/models/embedder/`. */
    val FILE_NAME: String get() = MODEL.fileName

    /** Exact served size of the primary file in bytes. */
    val APPROX_BYTES: Long get() = MODEL.sizeBytes

    /** Download URL of the primary file. */
    val DOWNLOAD_URL: String get() = MODEL.url

    /** SHA-256 of the primary file. */
    val SHA256: String get() = MODEL.sha256

    // ---- Shared download policy (see [ModelDownloadPolicy]) --------------------------------

    /** Classifies the free space on the target filesystem (1.25x headroom => `LOW`). */
    fun freeSpaceDecision(freeBytes: Long, requiredBytes: Long): FreeSpaceDecision =
        ModelDownloadPolicy.freeSpaceDecision(freeBytes, requiredBytes)

    /** The size that must be free to avoid the [FreeSpaceDecision.LOW] warning (download x 1.25). */
    fun requiredWithMargin(requiredBytes: Long): Long =
        ModelDownloadPolicy.requiredWithMargin(requiredBytes)

    /** Honest Russian explanation of a [FreeSpaceDecision]. */
    fun freeSpaceMessage(
        decision: FreeSpaceDecision,
        freeBytes: Long,
        requiredBytes: Long,
    ): String = ModelDownloadPolicy.freeSpaceMessage(decision, freeBytes, requiredBytes)

    /** Download completion in whole percent, clamped to `0..100`. */
    fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int =
        ModelDownloadPolicy.progressPercent(downloadedBytes, totalBytes)
}
