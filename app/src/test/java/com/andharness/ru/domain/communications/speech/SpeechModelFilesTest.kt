package com.andharness.ru.domain.communications.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [SpeechModelFiles] — the single, auditable place that deletes on-device speech-model
 * files. Files are removed only by an explicit delete; the model directory and siblings are never
 * targeted. Uses real temp files (no Android).
 */
class SpeechModelFilesTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "smf-${System.nanoTime()}").apply { mkdirs() }

    private fun seeded(dir: File, name: String, size: Long): File =
        File(dir, name).apply { outputStream().use { out -> out.write(ByteArray(size.toInt())) } }

    @Test
    fun removeFilesDeletesBothAndReturnsFreedBytes() {
        val dir = tempDir()
        val target = seeded(dir, "model.bin", 1000L)
        val partial = seeded(dir, "model.bin.part", 400L)
        assertTrue(target.isFile)
        assertTrue(partial.isFile)

        val freed = SpeechModelFiles.removeFiles(target, partial)

        assertFalse("the verified model must be gone", target.isFile)
        assertFalse("the partial must be gone", partial.isFile)
        assertEquals(1400L, freed)
    }

    @Test
    fun removeFilesNeverRemovesTheModelDirectoryOrSiblings() {
        val dir = tempDir()
        val target = seeded(dir, "model.bin", 500L)
        val sibling = seeded(dir, "unrelated.txt", 10L)

        SpeechModelFiles.removeFiles(target, File(dir, "missing.part"))

        assertTrue("the model directory itself must remain", dir.isDirectory)
        assertTrue("unrelated siblings must remain", sibling.isFile)
    }

    @Test
    fun removeFilesReturnsZeroWhenNothingExists() {
        val dir = tempDir()
        val freed = SpeechModelFiles.removeFiles(
            File(dir, "missing.bin"),
            File(dir, "missing.bin.part"),
        )
        assertEquals(0L, freed)
    }
}
