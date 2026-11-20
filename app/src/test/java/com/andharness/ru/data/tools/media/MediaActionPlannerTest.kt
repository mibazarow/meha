package com.andharness.ru.data.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [MediaActionPlanner]: the MIME fallback chain, URI scheme classification and the
 * MediaStore id extraction used by `media_open` / `media_share` and the `device_search` renderer.
 * All pure (no Android types), so they run without a Context or Robolectric.
 */
class MediaActionPlannerTest {

    @Test
    fun explicitMimeWins() {
        assertEquals("image/png", MediaActionPlanner.resolveMime("image/png", "image/jpeg"))
    }

    @Test
    fun detectedMimeUsedWhenNoExplicit() {
        assertEquals("image/jpeg", MediaActionPlanner.resolveMime(null, "image/jpeg"))
    }

    @Test
    fun blankExplicitFallsBackToDetected() {
        assertEquals("application/pdf", MediaActionPlanner.resolveMime("   ", "application/pdf"))
    }

    @Test
    fun fallbackWhenNothingKnown() {
        assertEquals(MediaActionPlanner.FALLBACK_MIME, MediaActionPlanner.resolveMime(null, null))
        assertEquals(MediaActionPlanner.FALLBACK_MIME, MediaActionPlanner.resolveMime("  ", ""))
    }

    @Test
    fun schemeDetection() {
        assertTrue(MediaActionPlanner.hasScheme("content://media/external/images/media/1"))
        assertTrue(MediaActionPlanner.hasScheme("https://example.com/a.jpg"))
        assertFalse(MediaActionPlanner.hasScheme("/storage/emulated/0/DCIM/a.jpg"))
        assertFalse(MediaActionPlanner.hasScheme("Pictures/a.jpg"))
    }

    @Test
    fun contentUriDetection() {
        assertTrue(MediaActionPlanner.isContentUri("content://media/external/file/9"))
        assertFalse(MediaActionPlanner.isContentUri("file:///storage/a.jpg"))
        assertFalse(MediaActionPlanner.isContentUri("/storage/a.jpg"))
    }

    @Test
    fun mediaStoreIdExtraction() {
        assertEquals(423L, MediaActionPlanner.mediaStoreId("content://media/external/images/media/423"))
        assertEquals(7L, MediaActionPlanner.mediaStoreId("content://media/external/file/7?x=1"))
        assertNull(MediaActionPlanner.mediaStoreId("/storage/emulated/0/a.jpg"))
        assertNull(MediaActionPlanner.mediaStoreId("content://media/external/images/media/notanumber"))
    }
}
