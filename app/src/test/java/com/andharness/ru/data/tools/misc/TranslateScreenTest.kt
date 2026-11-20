package com.andharness.ru.data.tools.misc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android-free parts of `translate_screen`: the OCR downscale decision, the OCR
 * text normalization and the output formatting. The capture/OCR/LLM I/O is device-dependent and is
 * verified on the device separately.
 */
class TranslateScreenTest {

    // ---------------------------------------------------------------- downscale

    @Test
    fun keepsSmallScreenshotsAtFullSize() {
        assertEquals(1, ocrDownscaleFactor(1080, 1920))
        assertEquals(1, ocrDownscaleFactor(1080, 2400))
        assertEquals(1, ocrDownscaleFactor(MAX_OCR_DIMEN, MAX_OCR_DIMEN))
    }

    @Test
    fun downscalesLargeScreenshotsToOneStep() {
        assertEquals(2, ocrDownscaleFactor(3200, 1600))
        assertEquals(2, ocrDownscaleFactor(1600, 3200))
        assertEquals(2, ocrDownscaleFactor(5120, 2880))
        // 7680 / 2560 = 3 exactly.
        assertEquals(3, ocrDownscaleFactor(7680, 4320))
    }

    @Test
    fun degradationIsSafeForDegenerateInput() {
        assertEquals(1, ocrDownscaleFactor(0, 0))
        assertEquals(1, ocrDownscaleFactor(-1, 500))
        assertEquals(1, ocrDownscaleFactor(100, 100, maxDim = 0))
    }

    @Test
    fun honoursCustomMaxDimension() {
        assertEquals(2, ocrDownscaleFactor(2000, 1000, maxDim = 1000))
        assertEquals(1, ocrDownscaleFactor(1000, 1000, maxDim = 1000))
    }

    // ---------------------------------------------------------------- OCR text cleanup

    @Test
    fun trimsLinesAndCollapsesBlankRuns() {
        assertEquals("Привет\n\nМир", cleanOcrText("  Привет \r\n\r\n\r\nМир \r\n"))
        assertEquals("a\n\nb", cleanOcrText("a\n\n\n\nb"))
    }

    @Test
    fun dropsLeadingTrailingAndStandaloneBlankLines() {
        assertEquals("только текст", cleanOcrText("\n\n  только текст  \n\n"))
        assertEquals("", cleanOcrText("   \n\n \t "))
        assertEquals("", cleanOcrText(""))
    }

    @Test
    fun normalizesLoneCarriageReturns() {
        assertEquals("one\ntwo", cleanOcrText("one\rtwo"))
    }

    // ---------------------------------------------------------------- output formatting

    @Test
    fun returnsOnlyTheTranslationByDefault() {
        val output = formatScreenTranslation("Hello", original = "Привет", includeOriginal = false)
        assertEquals("Hello", output)
    }

    @Test
    fun appendsTheRecognizedOriginalOnRequest() {
        val output = formatScreenTranslation("Hello", original = "Привет", includeOriginal = true)
        assertTrue(output.startsWith("Hello"))
        assertTrue(output.contains("Оригинал (распознанный текст):"))
        assertTrue(output.contains("Привет"))
    }

    @Test
    fun sharedTranslationPiecesAreReusedExplicitly() {
        // The screen tool reuses the translate_text prompt/cleanup verbatim.
        assertTrue(buildTranslatePrompt("x", "en", null, null).contains("Целевой язык: en"))
        assertEquals("Hello", cleanTranslation("«Hello»"))
        assertFalse(unsupportedLanguageMessage("Язык", "klingon", allowAuto = false).contains("auto"))
        assertTrue(unsupportedLanguageMessage("Исходный язык", "klingon", allowAuto = true).contains("auto"))
    }
}
