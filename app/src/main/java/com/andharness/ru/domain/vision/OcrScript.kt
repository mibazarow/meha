package com.andharness.ru.domain.vision

/**
 * Script the on-device OCR should recognize. ML Kit's text recognizer ships Latin/Chinese/
 * Devanagari/Japanese/Korean models only, so Cyrillic needs the bundled Tesseract engine.
 *
 * [tesseractLanguages] is the `+`-joined Tesseract language string loaded from the bundled
 * `assets/tessdata` directory (`rus+eng` also keeps Latin text on mixed Russian screens readable).
 * Reference: https://github.com/tesseract-ocr/tesseract/blob/main/doc/tesseract.1.asc
 */
enum class OcrScript(
    val storageValue: String,
    val tesseractLanguages: String,
) {
    CYRILLIC(storageValue = "CYRILLIC", tesseractLanguages = "rus+eng"),
    LATIN(storageValue = "LATIN", tesseractLanguages = "eng");

    companion object {
        val DEFAULT: OcrScript = CYRILLIC

        /**
         * Lenient parser kept for tests; production OCR always recognizes [DEFAULT] (`rus+eng`) and
         * does not read a stored setting.
         */
        fun fromStorage(value: String?): OcrScript {
            val raw = value?.trim().orEmpty()
            return entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
