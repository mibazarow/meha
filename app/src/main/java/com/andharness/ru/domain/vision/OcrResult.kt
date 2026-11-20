package com.andharness.ru.domain.vision

/**
 * Result of one OCR pass. [script] is the script that was *requested*; [engine] is the one that
 * actually ran, so a Tesseract failure that fell back to ML Kit Latin can be reported honestly
 * instead of pretending Cyrillic was recognized (see [note]).
 */
data class OcrResult(
    val text: String,
    val script: OcrScript,
    val engine: OcrEngine,
    val note: String? = null,
) {
    /**
     * One-line, human-readable statement of what actually ran — appended to tool output so a
     * Tesseract failure that degraded to Latin-only ML Kit is visible to the model and the user
     * instead of being silently swallowed.
     */
    val statusLine: String
        get() = buildString {
            append("OCR: ")
            append(if (engine == OcrEngine.TESSERACT) "Tesseract" else "ML Kit (fallback)")
            append(", язык: ")
            append(if (script == OcrScript.CYRILLIC) "кириллица+латиница" else "латиница")
            note?.takeIf { it.isNotBlank() }?.let {
                append(". ")
                append(it)
            }
        }
}
