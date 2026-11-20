package com.andharness.ru.domain.rag


/**
 * Local RAG over files (update.md idea #4): index local documents once, then answer questions from
 * them with citations (`sourcePath` + `chunkIndex`) instead of re-reading everything each time.
 *
 * Honest scope: the index stores extracted, chunked **text** — never a copy of a file — and ranking
 * is the shared lexical+synonym BM25 of [com.andharness.ru.domain.retrieval.TextRetriever]. Nothing
 * leaves the device.
 */

/** Result of one `rag_index` run. Counts and messages are exact — no invented success. */
data class RagIndexReport(
    val filesIndexed: Int,
    val filesSkipped: Int,
    val filesFailed: Int,
    val chunksStored: Int,
    /** Per-file honest failures (unsupported format, unreadable, empty text, path error). */
    val errors: List<String>,
    /** Files whose text was longer than the extractor cap and was therefore truncated. */
    val truncatedFiles: List<String>,
    /** Non-fatal remarks (e.g. the file-count cap was hit). */
    val notes: List<String> = emptyList()
)
