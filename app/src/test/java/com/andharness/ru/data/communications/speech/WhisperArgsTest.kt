package com.andharness.ru.data.communications.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [WhisperArgs] builder. Flags verified against whisper.cpp v1.9.4
 * `examples/cli/cli.cpp` (revision 927cfce34f31707e17f2bff35c349632fb9e2c3a).
 */
class WhisperArgsTest {

    private fun args(vadModelPath: String? = null): List<String> = WhisperArgs.buildArgs(
        modelPath = "/data/user/0/com.andharness.ru/files/ggml-tiny.bin",
        wavPath = "/data/user/0/com.andharness.ru/cache/audio.wav",
        outPrefix = "/data/user/0/com.andharness.ru/cache/whisper/whisper-1",
        threads = 4,
        language = "auto",
        vadModelPath = vadModelPath,
    )

    @Test
    fun buildsOrderedArgsWithJsonOutput() {
        assertEquals(
            listOf(
                "-m", "/data/user/0/com.andharness.ru/files/ggml-tiny.bin",
                "-f", "/data/user/0/com.andharness.ru/cache/audio.wav",
                "-l", "auto",
                "-t", "4",
                "-oj",
                "-of", "/data/user/0/com.andharness.ru/cache/whisper/whisper-1",
            ),
            args(),
        )
    }

    @Test
    fun includesOptionalVadAsAdjacentPair() {
        val result = args(vadModelPath = "/files/ggml-silero-v5.1.2.bin")

        val vadIndex = result.indexOf("--vad")
        assertTrue("--vad must be present for a VAD model", vadIndex >= 0)
        assertEquals("-vm", result[vadIndex + 1])
        assertEquals("/files/ggml-silero-v5.1.2.bin", result[vadIndex + 2])
        assertFalse("no VAD flags when no VAD model", args().contains("--vad"))
    }

    @Test
    fun neverEmitsNoTimestampsFlag() {
        // -nt would strip exactly the timestamps the JSON parser depends on.
        assertFalse(args().contains("-nt"))
        assertFalse(args(vadModelPath = "/vad.bin").contains("-nt"))
    }

    @Test
    fun hasNoDuplicateFlags() {
        val result = args(vadModelPath = "/vad.bin")
        for (flag in listOf("-m", "-f", "-l", "-t", "-oj", "-of", "--vad", "-vm")) {
            assertEquals("flag $flag must appear once", 1, result.count { it == flag })
        }
    }

    @Test
    fun languageAndThreadsAreCarriedThrough() {
        val result = WhisperArgs.buildArgs(
            modelPath = "/m.bin",
            wavPath = "/a.wav",
            outPrefix = "/o",
            threads = 2,
            language = "ru",
        )
        assertEquals("ru", result[result.indexOf("-l") + 1])
        assertEquals("2", result[result.indexOf("-t") + 1])
    }
}
