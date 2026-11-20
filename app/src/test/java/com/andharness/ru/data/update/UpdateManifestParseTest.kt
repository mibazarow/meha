package com.andharness.ru.data.update

import com.andharness.ru.domain.update.UpdateManifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the exact production manifest shape (including unknown keys the server may add) to prove
 * [UpdateManifest] tolerates a forward-compatible payload.
 *
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/
 */
class UpdateManifestParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Mirrors the production manifest shape, plus an unknown key. */
    private val productionJson = """
        {
          "versionCode": 17,
          "versionName": "1.0.16",
          "url": "https://example.com/updates/meha-17.apk",
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "sizeBytes": 12345678,
          "releasedAt": "2026-09-19T12:00:00Z",
          "notes": "Исправления и улучшения",
          "minSupportedVersionCode": 10,
          "mandatory": true,
          "someFutureKey": {"nested": true}
        }
    """.trimIndent()

    @Test
    fun parsesProductionShapeWithUnknownKeys() {
        val m = json.decodeFromString<UpdateManifest>(productionJson)

        assertEquals(17L, m.versionCode)
        assertEquals("1.0.16", m.versionName)
        assertEquals("https://example.com/updates/meha-17.apk", m.url)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", m.sha256)
        assertEquals(12345678L, m.sizeBytes)
        assertEquals("2026-09-19T12:00:00Z", m.releasedAt)
        assertEquals("Исправления и улучшения", m.notes)
        assertEquals(10L, m.minSupportedVersionCode)
        assertTrue(m.mandatory)
    }

    @Test
    fun missingOptionalKeysFallBackToDefaults() {
        val m = json.decodeFromString<UpdateManifest>(
            """{"versionCode":1,"url":"https://example.com/a.apk"}"""
        )

        assertEquals(1L, m.versionCode)
        assertEquals("https://example.com/a.apk", m.url)
        assertEquals("", m.versionName)
        assertEquals("", m.sha256)
        assertEquals(0L, m.sizeBytes)
        assertEquals("", m.releasedAt)
        assertEquals("", m.notes)
        assertEquals(0L, m.minSupportedVersionCode)
        assertFalse(m.mandatory)
    }
}
