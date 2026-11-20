package com.andharness.ru.domain.vision

/** Which engine actually produced an [OcrResult]. */
enum class OcrEngine {
    /** Bundled Tesseract4Android (`rus+eng` or `eng`), fully offline. */
    TESSERACT,

    /** ML Kit Latin recognizer — used only as a fallback when Tesseract cannot start. */
    ML_KIT
}
