package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [PdfFontAssets.select]: the PDF writer must pick the embedded Unicode font whenever
 * it can be loaded, and silently degrade to the standard-14 fallback when the asset is missing or
 * unreadable (a stripped build must not break PDF writing).
 */
class PdfFontAssetsTest {

    @Test
    fun bundlesDejaVuSansUnderTheFontsAssetDirectory() {
        assertEquals("fonts/DejaVuSans.ttf", PdfFontAssets.UNICODE_FONT_ASSET)
    }

    @Test
    fun picksTheUnicodeFontWhenItLoads() {
        val chosen = PdfFontAssets.select(loadUnicode = { "unicode" }, fallback = { "winansi" })
        assertEquals("unicode", chosen)
    }

    @Test
    fun fallsBackWhenTheAssetIsMissing() {
        val chosen = PdfFontAssets.select(loadUnicode = { null }, fallback = { "winansi" })
        assertEquals("winansi", chosen)
    }

    @Test
    fun fallsBackWhenLoadingTheAssetThrows() {
        var fallbackCalls = 0
        val chosen = PdfFontAssets.select(
            loadUnicode = { throw IllegalStateException("assets.open failed") },
            fallback = { fallbackCalls++; "winansi" },
        )
        assertEquals("winansi", chosen)
        assertEquals(1, fallbackCalls)
    }
}
