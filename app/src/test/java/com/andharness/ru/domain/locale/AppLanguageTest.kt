package com.andharness.ru.domain.locale

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [AppLanguage]: the persisted language and the tag read back from
 * `AppCompatDelegate.getApplicationLocales()` are normalized to one of the supported values, with
 * Russian as the fallback/default.
 */
class AppLanguageTest {

    @Test
    fun defaultIsRussian() {
        assertEquals("ru", AppLanguage.DEFAULT)
        assertEquals("ru", AppLanguage.RUSSIAN)
    }

    @Test
    fun supportedListsBothInOrder() {
        assertEquals(listOf("ru", "en"), AppLanguage.SUPPORTED)
    }

    @Test
    fun normalizeKeepsSupportedTags() {
        assertEquals("ru", AppLanguage.normalize("ru"))
        assertEquals("en", AppLanguage.normalize("en"))
    }

    @Test
    fun normalizeEnglishVariantsToEnglish() {
        assertEquals("en", AppLanguage.normalize("en-US"))
        assertEquals("en", AppLanguage.normalize("en_GB"))
        assertEquals("en", AppLanguage.normalize("EN"))
        assertEquals("en", AppLanguage.normalize(" En-us "))
    }

    @Test
    fun normalizeUnknownOrMissingFallsBackToRussian() {
        assertEquals("ru", AppLanguage.normalize("de"))
        assertEquals("ru", AppLanguage.normalize(""))
        assertEquals("ru", AppLanguage.normalize(null))
    }
}
