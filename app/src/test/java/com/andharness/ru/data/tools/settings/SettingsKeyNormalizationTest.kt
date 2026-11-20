package com.andharness.ru.data.tools.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [normalizeSettingsKey]. Pure string logic, no Android runtime needed.
 *
 * Android Settings keys are case-sensitive lowercase strings, so the get/set tools must normalize
 * any casing the model sends to the exact lowercase form:
 * https://developer.android.com/reference/android/provider/Settings.System#getString(android.content.ContentResolver,%20java.lang.String)
 * https://developer.android.com/reference/android/provider/Settings.System#putString(android.content.ContentResolver,%20java.lang.String,%20java.lang.String)
 */
class SettingsKeyNormalizationTest {

    @Test
    fun uppercasedKnownKeysMapToCanonicalLowerCase() {
        assertEquals("screen_brightness", normalizeSettingsKey("SCREEN_BRIGHTNESS"))
        assertEquals("wifi_on", normalizeSettingsKey("WIFI_ON"))
        assertEquals("android_id", normalizeSettingsKey("ANDROID_ID"))
    }

    @Test
    fun mixedCaseIsLowercased() {
        assertEquals("font_scale", normalizeSettingsKey("Font_Scale"))
        assertEquals("screen_off_timeout", normalizeSettingsKey("Screen_Off_Timeout"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("screen_brightness", normalizeSettingsKey("  SCREEN_BRIGHTNESS  "))
        assertEquals("wifi_on", normalizeSettingsKey("\twifi_on\n"))
    }

    @Test
    fun canonicalLowerCaseIsUnchanged() {
        assertEquals("screen_brightness", normalizeSettingsKey("screen_brightness"))
        assertEquals("enabled_accessibility_services", normalizeSettingsKey("enabled_accessibility_services"))
    }

    @Test
    fun blankInputStaysEmpty() {
        assertEquals("", normalizeSettingsKey(""))
        assertEquals("", normalizeSettingsKey("   "))
    }
}
