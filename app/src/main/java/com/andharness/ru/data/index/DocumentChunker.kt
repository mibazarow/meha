package com.andharness.ru.data.index

import com.andharness.ru.domain.formatting.WHITESPACE

/**
 * Pure, Android-free splitter that turns a document's extracted text into overlapping chunks the
 * on-device embedder can handle. Tokens are estimated with a cheap character heuristic
 * ([CHARS_PER_TOKEN] characters ≈ one token) instead of a real tokenizer, which keeps this a plain
 * JVM object that is unit-tested without a device.
 *
 * The heuristic is deliberately optimistic for Latin and UNDER-counts Cyrillic, which tokenises at
 * roughly 2 characters per token (see `LlmTokenEstimator.CHARS_PER_TOKEN_CYRILLIC`). So the real
 * token count of a [MAX_CHARS]-character chunk can be up to ≈[MAX_CHARS]/2 — the reason chunk sizing
 * is kept well below the embedder's per-request ceiling (the EmbeddingGemma 2 server runs with its
 * physical batch raised to the 2048-token context; see `LocalEmbeddingServer`). Oversized chunks made
 * the server answer `HTTP 500 … input (N tokens) is too large to process`, so a whole PDF silently
 * failed to index.
 *
 * The text is split on whitespace and packed greedily into chunks of at most [MAX_CHARS] characters;
 * when a chunk is flushed, a trailing slice of up to [OVERLAP_CHARS] characters is carried into the
 * next chunk (snapped to a word boundary) so a sentence spanning a boundary is not lost. Any single
 * "word" longer than the chunk budget (e.g. a base64 blob) is hard-split so every returned chunk is
 * bounded. Blank input yields an empty list.
 */
object DocumentChunker {

    /** Characters per estimated token, used only to size chunks (no tokenizer dependency). */
    const val CHARS_PER_TOKEN = 4

    /** Nominal target chunk size in tokens (mid of the embedder-safe band). */
    const val TARGET_TOKENS = 350

    /**
     * Hard upper bound on a chunk, in tokens as measured by the [CHARS_PER_TOKEN] heuristic.
     *
     * Kept at 400 (not 500) so that a chunk's REAL token count — up to ≈2× this for Cyrillic, which
     * tokenises far denser than the 4-chars/token heuristic assumes — stays comfortably under the
     * embedder's per-request limit. The EmbeddingGemma 2 server now runs with its physical batch
     * raised to its 2048-token context (`LocalEmbeddingServer`), so ~800 real tokens leaves a wide
     * margin. This is what prevents the `HTTP 500 … input (N tokens) is too large` that dropped every
     * PDF chunk (observed: 533–966 tokens) when the server still ran llama.cpp's default 512 batch.
     */
    const val MAX_TOKENS = 400

    /** Hard upper bound on a chunk, in characters ([MAX_TOKENS] × [CHARS_PER_TOKEN]). */
    const val MAX_CHARS = MAX_TOKENS * CHARS_PER_TOKEN

    /** Characters of overlap carried into the next chunk (≈50 tokens). */
    const val OVERLAP_CHARS = 200

    /** Estimated token count of [text] using the [CHARS_PER_TOKEN] heuristic (never negative). */
    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Splits [text] into word-aligned chunks of at most [maxChars] characters with up to
     * [overlapChars] characters of overlap between consecutive chunks. Returns an empty list for
     * blank input; a non-blank text shorter than [maxChars] yields exactly one chunk.
     */
    fun chunk(
        text: String,
        maxChars: Int = MAX_CHARS,
        overlapChars: Int = OVERLAP_CHARS,
    ): List<String> {
        val cap = maxChars.coerceAtLeast(1)
        val overlap = overlapChars.coerceIn(0, cap - 1)
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= cap) return listOf(normalized)

        // Split on whitespace, then hard-split any word longer than the budget so no chunk overflows.
        val words = normalized.split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .flatMap { word -> if (word.length <= cap) listOf(word) else word.chunked(cap) }

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (word in words) {
            val extra = if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() && current.length + extra + word.length > cap) {
                val flushed = current.toString()
                chunks += flushed
                current.setLength(0)
                val carry = tailOnWordBoundary(flushed, overlap)
                if (carry.isNotEmpty()) current.append(carry)
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /**
     * The longest suffix of [text] no longer than [maxChars] that starts on a word boundary, or an
     * empty string when none fits. Used for the overlap carried between chunks.
     */
    private fun tailOnWordBoundary(text: String, max: Int): String {
        if (max <= 0 || text.isEmpty()) return ""
        if (text.length <= max) return text
        var start = text.length - max
        while (start < text.length && !text[start].isWhitespace()) start++
        while (start < text.length && text[start].isWhitespace()) start++
        return if (start >= text.length) "" else text.substring(start)
    }
}
