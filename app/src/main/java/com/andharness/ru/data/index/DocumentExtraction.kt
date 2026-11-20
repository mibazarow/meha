package com.andharness.ru.data.index

import com.andharness.ru.data.tools.documents.DocumentFormat
import com.andharness.ru.data.tools.documents.DocumentTextExtractor

/**
 * Honest, Android-free classification of one document's text-extraction outcome for the device
 * document indexer. It separates the three cases the pass counters must never conflate:
 *
 * - [Text] — usable text was extracted and should be chunked + embedded;
 * - [Skipped] — the document is readable but carries nothing to index (an unsupported format, no
 *   extractable text, or a deliberately bounded oversized file) — an honest *skip*, not an error;
 * - [Failed] — a genuine error prevented extraction (unreadable / corrupt / I/O); the [Failed.reason]
 *   is logged by the caller so a bad file is reported honestly instead of being lost silently as an
 *   anonymous `failed` count.
 *
 * Kept free of Android so the classification is unit-tested on the JVM. Kotlin sealed hierarchies are
 * exhaustively matched with `when` (no `else` needed):
 * https://kotlinlang.org/docs/sealed-classes.html
 */
sealed interface DocumentExtraction {

    /** Usable [text] (already trimmed) plus provenance: whether OCR was needed and how many pages. */
    data class Text(val text: String, val ocrUsed: Boolean, val pages: Int) : DocumentExtraction

    /** Readable but nothing to index; [reason] is informational (logged, not counted as an error). */
    data class Skipped(val reason: String) : DocumentExtraction

    /** A genuine extraction error; [reason] carries the failure and is logged by the caller. */
    data class Failed(val reason: String) : DocumentExtraction

    companion object {

        /**
         * Classifies a completed, non-OCR [DocumentTextExtractor] result into an honest outcome. A
         * failure on a format the extractor does not index is a skip (unsupported), while a failure on
         * an indexable format is a genuine [Failed] carrying the extractor's reason.
         */
        fun classify(
            format: DocumentFormat,
            extracted: DocumentTextExtractor.Extracted,
        ): DocumentExtraction = when (extracted) {
            is DocumentTextExtractor.Extracted.Failure ->
                if (DocumentTextExtractor.isIndexable(format)) {
                    Failed(extracted.message)
                } else {
                    Skipped("unsupported format ${format.name}")
                }

            is DocumentTextExtractor.Extracted.Success -> {
                val text = extracted.text.trim()
                if (text.isEmpty()) {
                    Skipped("no extractable text")
                } else {
                    Text(text, ocrUsed = false, pages = 0)
                }
            }
        }

        /** A genuine failure built from the caught [cause], with a one-line human-readable reason. */
        fun failed(cause: Throwable): Failed = Failed(describe(cause))

        /** One-line description of a throwable (`Type: message`) for an honest failure reason. */
        fun describe(cause: Throwable): String {
            val message = cause.message?.takeIf { it.isNotBlank() }
            return if (message == null) cause.javaClass.name else "${cause.javaClass.name}: $message"
        }
    }
}
