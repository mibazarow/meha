package com.andharness.ru.domain.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrScriptTest {

    @Test
    fun `default is cyrillic`() {
        assertEquals(OcrScript.CYRILLIC, OcrScript.DEFAULT)
    }

    @Test
    fun `cyrillic loads russian and english models`() {
        assertEquals("rus+eng", OcrScript.CYRILLIC.tesseractLanguages)
    }

    @Test
    fun `latin loads english only`() {
        assertEquals("eng", OcrScript.LATIN.tesseractLanguages)
    }

    @Test
    fun `parses stored values case-insensitively and trims`() {
        assertEquals(OcrScript.LATIN, OcrScript.fromStorage("latin"))
        assertEquals(OcrScript.LATIN, OcrScript.fromStorage("  LATIN "))
        assertEquals(OcrScript.CYRILLIC, OcrScript.fromStorage("cyrillic"))
    }

    @Test
    fun `unknown or missing storage value falls back to the default`() {
        assertEquals(OcrScript.DEFAULT, OcrScript.fromStorage(null))
        assertEquals(OcrScript.DEFAULT, OcrScript.fromStorage(""))
        assertEquals(OcrScript.DEFAULT, OcrScript.fromStorage("klingon"))
    }

    @Test
    fun `storage value round-trips`() {
        OcrScript.entries.forEach { script ->
            assertEquals(script, OcrScript.fromStorage(script.storageValue))
        }
    }

    @Test
    fun `ocr result keeps requested script and actual engine`() {
        val result = OcrResult(
            text = "ПРИВЕТ",
            script = OcrScript.CYRILLIC,
            engine = OcrEngine.ML_KIT,
            note = "Tesseract не инициализирован"
        )
        assertEquals("ПРИВЕТ", result.text)
        assertEquals(OcrScript.CYRILLIC, result.script)
        assertEquals(OcrEngine.ML_KIT, result.engine)
        assertEquals("Tesseract не инициализирован", result.note)
        assertNull(OcrResult("", OcrScript.LATIN, OcrEngine.TESSERACT).note)
    }

    @Test
    fun `status line names the engine and script honestly`() {
        val tesseract = OcrResult("ПРИВЕТ", OcrScript.CYRILLIC, OcrEngine.TESSERACT).statusLine
        assertEquals("OCR: Tesseract, язык: кириллица+латиница", tesseract)

        val latin = OcrResult("HELLO", OcrScript.LATIN, OcrEngine.TESSERACT).statusLine
        assertEquals("OCR: Tesseract, язык: латиница", latin)
    }

    @Test
    fun `status line reports the ML Kit fallback and its reason`() {
        val line = OcrResult(
            text = "npuber",
            script = OcrScript.CYRILLIC,
            engine = OcrEngine.ML_KIT,
            note = "Tesseract не инициализирован"
        ).statusLine
        assertTrue(line.contains("ML Kit (fallback)"))
        assertTrue(line.contains("кириллица+латиница"))
        assertTrue(line.contains("Tesseract не инициализирован"))
    }
}
