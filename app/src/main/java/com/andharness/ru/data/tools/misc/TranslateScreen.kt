package com.andharness.ru.data.tools.misc

/**
 * Android-free helpers behind `translate_screen` (checklist §3): bounding the screenshot before OCR
 * and normalizing the OCR text before it is sent to the LLM. Kept pure so both are unit-tested on the
 * JVM (`TranslateScreenTest`); the capture/OCR/LLM I/O lives in the handler.
 */

/**
 * Longest side kept when downscaling before OCR. Chosen above the long side of a typical phone
 * screenshot (1080×~2400) so normal screenshots go to OCR at full resolution and only unusually large
 * captures are shrunk; OCR accuracy on small UI text is preserved.
 */
internal const val MAX_OCR_DIMEN = 2560

/**
 * Integer downscale factor for OCR: 1 when the bitmap already fits [maxDim], otherwise the smallest
 * integer factor that brings the LONGEST side within [maxDim]. Pure and unit-tested.
 */
internal fun ocrDownscaleFactor(width: Int, height: Int, maxDim: Int = MAX_OCR_DIMEN): Int {
    val longest = maxOf(width, height)
    if (longest <= 0 || maxDim <= 0 || longest <= maxDim) return 1
    return (longest + maxDim - 1) / maxDim
}

/**
 * Normalizes raw OCR output: CRLF/CR to LF, trims each line, drops blank lines and collapses runs of
 * blank lines to at most one, then trims the whole text. Pure and unit-tested.
 */
internal fun cleanOcrText(raw: String): String {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder()
    var blankPending = false
    for (line in normalized.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            if (out.isNotEmpty()) blankPending = true
            continue
        }
        if (out.isNotEmpty()) out.append(if (blankPending) "\n\n" else "\n")
        out.append(trimmed)
        blankPending = false
    }
    return out.toString().trim()
}

/**
 * Renders the `translate_screen` answer: the translation, optionally followed by the recognized
 * original text. Pure and unit-tested.
 */
internal fun formatScreenTranslation(
    translation: String,
    original: String,
    includeOriginal: Boolean,
): String = buildString {
    appendLine(translation.trim())
    if (includeOriginal) {
        appendLine()
        appendLine("Оригинал (распознанный текст):")
        append(original.trim())
    }
}.trim()
