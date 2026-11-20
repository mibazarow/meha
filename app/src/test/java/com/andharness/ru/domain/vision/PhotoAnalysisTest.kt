package com.andharness.ru.domain.vision

import com.andharness.ru.domain.vision.PhotoAnalysis.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the camera photo-analysis policy (no Android APIs): the vision decision, the
 * honest OCR-fallback wording and the description cleaner.
 */
class PhotoAnalysisTest {

    @Test
    fun `analyze off never touches the model`() {
        val plan = PhotoAnalysis.plan(analyzeRequested = false, visionSupported = true)
        assertEquals(Mode.NONE, plan.mode)
    }

    @Test
    fun `vision supported sends the photo to the model`() {
        val plan = PhotoAnalysis.plan(analyzeRequested = true, visionSupported = true)
        assertEquals(Mode.VISION, plan.mode)
    }

    @Test
    fun `no vision falls back to OCR`() {
        val plan = PhotoAnalysis.plan(analyzeRequested = true, visionSupported = false)
        assertEquals(Mode.OCR, plan.mode)
    }

    @Test
    fun `ocr wording states the image was not sent to the model`() {
        val text = PhotoAnalysis.renderOcr("ПРИВЕТ МИР", "OCR: Tesseract, язык: кириллица+латиница")
        assertTrue(text.contains(PhotoAnalysis.VISION_UNAVAILABLE_NOTE))
        assertTrue(text.contains("ПРИВЕТ МИР"))
        assertTrue(text.contains("Tesseract"))
    }

    @Test
    fun `empty ocr is reported honestly instead of blank`() {
        val text = PhotoAnalysis.renderOcr("   ", null)
        assertTrue(text.contains(PhotoAnalysis.VISION_UNAVAILABLE_NOTE))
        assertTrue(text.contains("Текст на изображении не найден."))
    }

    @Test
    fun `ocr unavailable wording includes the reason`() {
        val text = PhotoAnalysis.renderOcrUnavailable("модель не настроена")
        assertTrue(text.contains(PhotoAnalysis.VISION_UNAVAILABLE_NOTE))
        assertTrue(text.contains("модель не настроена"))
    }

    @Test
    fun `vision rendering keeps the description`() {
        val text = PhotoAnalysis.renderVision("На столе стоит чашка.")
        assertTrue(text.contains("На столе стоит чашка."))
    }

    @Test
    fun `cleanDescription strips quotes and fences`() {
        assertEquals("кот на окне", PhotoAnalysis.cleanDescription("  «кот на окне»  "))
        assertEquals("кот на окне", PhotoAnalysis.cleanDescription("```\nкот на окне\n```"))
        assertEquals("", PhotoAnalysis.cleanDescription("   "))
    }
}
