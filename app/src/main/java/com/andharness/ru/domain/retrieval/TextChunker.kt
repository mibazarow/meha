package com.andharness.ru.domain.retrieval

/**
 * Splits extracted document text into overlapping chunks for the local RAG index
 * (update.md idea #4). Pure Kotlin, zero dependencies, JVM-testable — the chunker is the only
 * place that decides chunk size/overlap so indexing and tests agree on one definition.
 *
 * Behaviour:
 * - The text is normalised first: `\r\n`/`\r` become `\n`, runs of spaces/tabs collapse to a single
 *   space (newlines are preserved). Every [Chunk.startChar] is an offset into this normalised text.
 * - Packing respects sentence boundaries: a chunk never ends in the middle of a sentence unless that
 *   single sentence is longer than [CHUNK_CHARS], in which case it is hard-split (honestly, at a
 *   character boundary).
 * - Adjacent chunks overlap by up to [OVERLAP_CHARS] (~15% of the default chunk) so a term spanning
 *   a boundary is still found by at least one chunk.
 */
object TextChunker {

    /** Target chunk size in characters. */
    const val CHUNK_CHARS = 800

    /** Target overlap between adjacent chunks in characters (~15% of [CHUNK_CHARS]). */
    const val OVERLAP_CHARS = 120

    /** One stored chunk: [index] is 0-based within its source, [startChar] offsets the normalised text. */
    data class Chunk(val index: Int, val text: String, val startChar: Int)

    /**
     * Chunks [text] with [chunkChars] size and [overlapChars] overlap. Returns an empty list for
     * blank input. [overlapChars] is clamped to `0..chunkChars-1` so progress is always guaranteed.
     */
    fun chunk(
        text: String,
        chunkChars: Int = CHUNK_CHARS,
        overlapChars: Int = OVERLAP_CHARS
    ): List<Chunk> {
        require(chunkChars > 0) { "chunkChars must be positive" }
        val effectiveOverlap = overlapChars.coerceIn(0, chunkChars - 1)
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()
        val sentences = splitSentences(normalized)
        if (sentences.isEmpty()) return emptyList()

        val result = ArrayList<Chunk>()
        var start = 0
        while (start < sentences.size) {
            // Greedily take whole sentences while they fit into one chunk.
            var end = start
            var length = 0
            while (end < sentences.size && length + sentences[end].text.length <= chunkChars) {
                length += sentences[end].text.length
                end++
            }
            if (end == start) {
                // A single sentence longer than the chunk: split it at character boundaries.
                emitHardSplit(sentences[start], chunkChars, effectiveOverlap, result)
                start++
                continue
            }
            val body = buildString { for (i in start until end) append(sentences[i].text) }
            val trimmed = body.trimEnd()
            if (trimmed.isNotBlank()) result += Chunk(result.size, trimmed, sentences[start].start)

            // Next chunk reuses the trailing sentences of this one, up to the overlap budget.
            var next = end
            var overlap = 0
            while (next - 1 >= start && overlap + sentences[next - 1].text.length <= effectiveOverlap) {
                overlap += sentences[next - 1].text.length
                next--
            }
            if (next <= start) next = start + 1
            start = next
        }
        return result
    }

    private fun emitHardSplit(
        sentence: Span,
        chunkChars: Int,
        overlapChars: Int,
        out: MutableList<Chunk>
    ) {
        val step = (chunkChars - overlapChars).coerceAtLeast(1)
        var offset = 0
        while (offset < sentence.text.length) {
            val end = minOf(offset + chunkChars, sentence.text.length)
            val piece = sentence.text.substring(offset, end).trimEnd()
            if (piece.isNotBlank()) out += Chunk(out.size, piece, sentence.start + offset)
            if (end >= sentence.text.length) break
            offset += step
        }
    }

    private data class Span(val text: String, val start: Int)

    /** Splits normalised [text] into sentences/paragraph lines, each keeping its closing whitespace. */
    private fun splitSentences(text: String): List<Span> {
        val spans = ArrayList<Span>()
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            var boundary = false
            if (c == '\n') {
                boundary = true
                i++
            } else if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < n && text[j] in CLOSERS) j++
                if (j >= n || text[j].isWhitespace()) {
                    boundary = true
                    i = j
                }
            }
            if (boundary) {
                appendSpan(spans, text.substring(start, i), start)
                start = i
            } else {
                i++
            }
        }
        if (start < n) appendSpan(spans, text.substring(start), start)
        return spans
    }

    /**
     * Appends a span, folding whitespace-only fragments into the previous span so spans stay
     * contiguous: the concatenation of consecutive spans is then exactly the source substring they
     * cover, which keeps [Chunk.startChar] offsets meaningful.
     */
    private fun appendSpan(spans: MutableList<Span>, raw: String, start: Int) {
        if (raw.isBlank()) {
            if (spans.isNotEmpty()) {
                val last = spans.removeAt(spans.size - 1)
                spans += Span(last.text + raw, last.start)
            }
            return
        }
        spans += Span(raw, start)
    }

    private const val CLOSERS = "»\"')]”"

    /** `\r\n`/`\r` → `\n`; space/tab runs → one space; newlines preserved. */
    private fun normalize(text: String): String {
        val unified = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(unified.length)
        var lastWasSpace = false
        for (ch in unified) {
            if (ch == ' ' || ch == '\t') {
                if (!lastWasSpace) out.append(' ')
                lastWasSpace = true
            } else {
                out.append(ch)
                lastWasSpace = ch == '\n'
            }
        }
        return out.toString()
    }
}
