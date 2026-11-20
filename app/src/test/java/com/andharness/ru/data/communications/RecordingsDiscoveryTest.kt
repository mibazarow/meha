package com.andharness.ru.data.communications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure discovery helpers of [RecordingsScanner] / [RecordingImporter]:
 * the audio extension/MIME filter, the dedup-key helper and the recordings-folder matcher.
 * No Android APIs are touched.
 */
class RecordingsDiscoveryTest {

    // ---- audio extension / MIME filter ------------------------------------------------------

    @Test
    fun audioMimeTypeMatchesOnlyAudioStreams() {
        assertTrue(isAudioMimeType("audio/mpeg"))
        assertTrue(isAudioMimeType("AUDIO/OGG"))
        assertFalse(isAudioMimeType("video/mp4"))
        assertFalse(isAudioMimeType("application/ogg"))
        assertFalse(isAudioMimeType(null))
        assertFalse(isAudioMimeType("  "))
    }

    @Test
    fun extensionIsLowerCasedAndDotSeparated() {
        assertEquals("mp3", audioExtensionOf("track.MP3"))
        assertEquals("m4a", audioExtensionOf("  voice.m4a "))
        assertEquals("opus", audioExtensionOf("a.b.opus"))
        assertNull(audioExtensionOf("noextension"))
        assertNull(audioExtensionOf("trailing."))
        assertNull(audioExtensionOf(null))
    }

    @Test
    fun looksLikeAudioAcceptsKnownExtensionWithoutMime() {
        assertTrue(looksLikeAudio("recording.m4a", null))
        assertTrue(looksLikeAudio("call.OPUS", "  "))
        assertTrue(looksLikeAudio(null, "audio/x-wav"))
    }

    @Test
    fun looksLikeAudioRejectsNonAudioRows() {
        assertFalse(looksLikeAudio("photo.jpg", "image/jpeg"))
        assertFalse(looksLikeAudio("clip.mp4", "video/mp4"))
        assertFalse(looksLikeAudio(null, null))
    }

    // ---- dedup key --------------------------------------------------------------------------

    @Test
    fun contentUriKeyCarriesSizeAndNormalisesCase() {
        assertEquals(
            recordingDedupKey("content://media/external/audio/media/42", 1024L),
            recordingDedupKey("  CONTENT://Media/External/Audio/Media/42  ", 1024L),
        )
    }

    @Test
    fun contentUriKeyChangesWhenSizeChanges() {
        assertNotEquals(
            recordingDedupKey("content://media/external/audio/media/42", 1024L),
            recordingDedupKey("content://media/external/audio/media/42", 2048L),
        )
    }

    @Test
    fun pathKeyIsTheFallbackIdentity() {
        assertEquals(
            recordingDedupKey("/storage/emulated/0/Recordings/a.m4a", 0L),
            recordingDedupKey("/storage/emulated/0/Recordings/a.m4a", 999L),
        )
        assertNotEquals(
            recordingDedupKey("/storage/emulated/0/Recordings/a.m4a", 0L),
            recordingDedupKey("/storage/emulated/0/Recordings/b.m4a", 0L),
        )
    }

    @Test
    fun uriAndPathNeverCollide() {
        assertFalse(isContentUri("/storage/emulated/0/a.m4a"))
        assertTrue(isContentUri("content://media/external/audio/media/1"))
        assertTrue(isContentUri("CONTENT://x/y"))
    }

    // ---- recordings-folder filter -----------------------------------------------------------

    @Test
    fun emptyFolderSetAcceptsEverything() {
        assertTrue(matchesRecordingsFolder("/storage/emulated/0/Music/a.mp3", emptySet()))
        assertTrue(matchesRecordingsFolder(null, emptySet()))
    }

    @Test
    fun folderMatchesByTrailingSegment() {
        val folders = setOf("Recordings")
        assertTrue(matchesRecordingsFolder("/storage/emulated/0/Recordings/a.m4a", folders))
        assertTrue(matchesRecordingsFolder("/storage/emulated/0/Recordings/sub/a.m4a", folders))
        assertFalse(matchesRecordingsFolder("/storage/emulated/0/Music/a.mp3", folders))
    }

    @Test
    fun folderMatchesByAbsolutePathAndIsCaseInsensitive() {
        val folders = setOf("/storage/emulated/0/Recordings/")
        assertTrue(matchesRecordingsFolder("/STORAGE/EMULATED/0/Recordings/a.m4a", folders))
        assertFalse(matchesRecordingsFolder("/storage/emulated/0/RecordingsExtra/a.m4a", folders))
    }

    @Test
    fun unknownPathIsRejectedWhenAFilterIsSet() {
        assertFalse(matchesRecordingsFolder(null, setOf("Recordings")))
        assertFalse(matchesRecordingsFolder("   ", setOf("Recordings")))
    }

    // ---- safe import file name --------------------------------------------------------------

    @Test
    fun safeImportFileNameStripsSeparatorsAndLeadingDots() {
        assertEquals("a_b.m4a", safeImportFileName("a/b.m4a"))
        assertEquals("voice.m4a", safeImportFileName("...voice.m4a"))
        assertNull(safeImportFileName("   "))
        assertNull(safeImportFileName(null))
    }
}
