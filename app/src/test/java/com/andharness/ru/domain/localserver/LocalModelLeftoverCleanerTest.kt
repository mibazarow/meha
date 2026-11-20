package com.andharness.ru.domain.localserver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [LocalModelLeftoverCleaner] — the policy for reclaiming the orphaned files of
 * the removed on-device local chat model. Uses real temp dirs (no Android). The critical invariant
 * under test: the cleanup targets only `meha-qwen3vl-v2-*` files and `slots/`, and NEVER the
 * device-search embedder (`models/embedder/`) or the `models/` directory itself.
 */
class LocalModelLeftoverCleanerTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "lmlc-${System.nanoTime()}").apply { mkdirs() }

    private fun seeded(dir: File, name: String, size: Long = 8L): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            outputStream().use { out -> out.write(ByteArray(size.toInt())) }
        }

    private fun modelsDirOf(filesDir: File): File =
        File(filesDir, LocalModelLeftoverCleaner.MODELS_DIR).apply { mkdirs() }

    @Test
    fun pathsToDeleteTargetsLegacyFilesAndSlots() {
        val filesDir = tempDir()
        val modelsDir = modelsDirOf(filesDir)

        val paths = LocalModelLeftoverCleaner.pathsToDelete(modelsDir, filesDir)

        assertTrue(File(modelsDir, LocalModelLeftoverCleaner.LEGACY_WEIGHTS_FILE) in paths)
        assertTrue(File(modelsDir, LocalModelLeftoverCleaner.LEGACY_MMPROJ_FILE) in paths)
        assertTrue(File(filesDir, LocalModelLeftoverCleaner.SLOTS_DIR) in paths)
    }

    @Test
    fun pathsToDeleteCatchesUnknownLegacyLeftovers() {
        val filesDir = tempDir()
        val modelsDir = modelsDirOf(filesDir)
        val extra = seeded(modelsDir, "meha-qwen3vl-v2-mmproj-extra.gguf")

        val paths = LocalModelLeftoverCleaner.pathsToDelete(modelsDir, filesDir)

        assertTrue("an unlisted meha-qwen3vl-v2-* leftover must be cleaned", extra in paths)
    }

    @Test
    fun pathsToDeleteNeverTargetsEmbedderModelsDirOrSiblings() {
        val filesDir = tempDir()
        val modelsDir = modelsDirOf(filesDir)
        val embedderFile = seeded(File(modelsDir, "embedder"), "embedder.onnx")
        val unrelated = seeded(modelsDir, "some-other-model.gguf")
        val legacy = seeded(modelsDir, LocalModelLeftoverCleaner.LEGACY_WEIGHTS_FILE)

        val paths = LocalModelLeftoverCleaner.pathsToDelete(modelsDir, filesDir)

        assertTrue("the legacy file itself must be cleaned", legacy in paths)
        assertFalse("the embedder must NOT be touched", embedderFile in paths)
        assertFalse("models/embedder must NOT be touched", File(modelsDir, "embedder") in paths)
        assertFalse("unrelated siblings must NOT be touched", unrelated in paths)
        assertFalse("the models dir itself must NOT be removed", modelsDir in paths)
        assertFalse("filesDir itself must NOT be removed", filesDir in paths)
    }

    @Test
    fun cleanInvokesDeleteForEveryPathAndCountsSuccesses() {
        val filesDir = tempDir()
        val modelsDir = modelsDirOf(filesDir)
        seeded(modelsDir, LocalModelLeftoverCleaner.LEGACY_WEIGHTS_FILE)
        seeded(modelsDir, LocalModelLeftoverCleaner.LEGACY_MMPROJ_FILE)
        seeded(modelsDir, "meha-qwen3vl-v2-cache.bin")
        val slots = File(filesDir, LocalModelLeftoverCleaner.SLOTS_DIR).apply { mkdirs() }
        seeded(slots, "slot0.bin")

        val expected = LocalModelLeftoverCleaner.pathsToDelete(modelsDir, filesDir)
        val seen = mutableListOf<File>()
        val removed = LocalModelLeftoverCleaner.clean(filesDir, modelsDir) { path ->
            seen += path
            path.deleteRecursively()
        }

        assertEquals(expected.toSet(), seen.toSet())
        assertEquals("4 existing paths (3 model files + slots dir)", 4, removed)
        assertFalse(File(modelsDir, LocalModelLeftoverCleaner.LEGACY_WEIGHTS_FILE).exists())
        assertFalse(File(modelsDir, "meha-qwen3vl-v2-cache.bin").exists())
        assertFalse("the slots tree must be gone", slots.exists())
    }

    @Test
    fun cleanSwallowsDeleteFailuresAndNeverThrows() {
        val filesDir = tempDir()
        val modelsDir = modelsDirOf(filesDir)

        val removed = LocalModelLeftoverCleaner.clean(filesDir, modelsDir) {
            throw IllegalStateException("simulated delete failure")
        }

        assertEquals("a throwing delete must be swallowed", 0, removed)
    }
}
