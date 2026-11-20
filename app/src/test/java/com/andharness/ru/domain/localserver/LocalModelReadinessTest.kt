package com.andharness.ru.domain.localserver

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [LocalModelReadiness] — the on-disk completeness check (existence + exact size)
 * that the state resolution relies on. Uses real temp files (no Android).
 */
class LocalModelReadinessTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "lmr-${System.nanoTime()}").apply { mkdirs() }

    private fun seeded(dir: File, name: String, size: Long): File =
        File(dir, name).apply { outputStream().use { out -> out.write(ByteArray(size.toInt())) } }

    @Test
    fun completeOnlyWhenSizeMatchesExactly() {
        val dir = tempDir()
        val exact = seeded(dir, "model.gguf", 1000L)
        val truncated = seeded(dir, "short.gguf", 400L)

        assertTrue(LocalModelReadiness.isComplete(exact, 1000L))
        assertFalse("a truncated file is not ready", LocalModelReadiness.isComplete(truncated, 1000L))
        assertFalse(
            "a larger-than-expected file is not ready",
            LocalModelReadiness.isComplete(seeded(dir, "long.gguf", 1200L), 1000L),
        )
    }

    @Test
    fun missingFileIsNotComplete() {
        val dir = tempDir()
        assertFalse(LocalModelReadiness.isComplete(File(dir, "nope.gguf"), 1000L))
    }

    @Test
    fun emptyFileIsNotCompleteForAnyExpectedSize() {
        val dir = tempDir()
        val empty = seeded(dir, "empty.gguf", 0L)
        assertFalse(LocalModelReadiness.isComplete(empty, 1000L))
    }

    @Test
    fun allCompleteRequiresEveryFileAtExactSize() {
        val dir = tempDir()
        val weights = seeded(dir, "w.gguf", 1000L)
        val mmproj = seeded(dir, "m.gguf", 500L)

        assertTrue(LocalModelReadiness.allComplete(listOf(weights to 1000L, mmproj to 500L)))
        assertFalse(
            "one truncated required file makes the whole set not ready",
            LocalModelReadiness.allComplete(listOf(weights to 1000L, mmproj to 999L)),
        )
        assertFalse(
            "one missing required file makes the whole set not ready",
            LocalModelReadiness.allComplete(listOf(weights to 1000L, File(dir, "gone.gguf") to 500L)),
        )
    }

    @Test
    fun emptyListIsNeverReady() {
        assertFalse(LocalModelReadiness.allComplete(emptyList()))
    }
}
