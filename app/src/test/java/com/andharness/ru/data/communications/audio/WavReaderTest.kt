package com.andharness.ru.data.communications.audio

import com.andharness.ru.bg.WavHeader
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [WavReader] (round-trip against [WavHeader]) and [sliceMs] (clamping and edge
 * cases). No Android APIs are touched; files are written to the OS temp directory.
 */
class WavReaderTest {

    private fun tempWav(samples: ShortArray, sampleRate: Int = 16_000, channels: Int = 1): File {
        val payload = ByteArray(samples.size * 2)
        var i = 0
        for (sample in samples) {
            val value = sample.toInt()
            payload[i++] = (value and 0xFF).toByte()
            payload[i++] = ((value shr 8) and 0xFF).toByte()
        }
        return File.createTempFile("wavreader-", ".wav").apply {
            deleteOnExit()
            outputStream().use { out ->
                out.write(WavHeader.build(sampleRate, channels, 16, payload.size))
                out.write(payload)
            }
        }
    }

    @Test
    fun roundTripPreservesSamplesAndMetadata() {
        val samples = shortArrayOf(0, 1, -1, 32_767, -32_768, 1234, -4321)

        val audio = WavReader.read(tempWav(samples, sampleRate = 16_000, channels = 1))

        assertEquals(16_000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertArrayEquals(samples, audio.samples)
        assertArrayEquals(audio.samples, WavReader.readSamples(tempWav(samples)))
    }

    @Test
    fun readsEmptyPayloadAsEmptyArray() {
        val audio = WavReader.read(tempWav(ShortArray(0)))

        assertEquals(0, audio.samples.size)
        assertEquals(16_000, audio.sampleRate)
    }

    @Test
    fun downmixesStereoToMono() {
        // Two interleaved frames: (1000, 3000) and (-2000, 0) => averages 2000 and -1000.
        val interleaved = shortArrayOf(1000, 3000, -2000, 0)

        val audio = WavReader.read(tempWav(interleaved, channels = 2))

        assertEquals(1, audio.channels)
        assertArrayEquals(shortArrayOf(2000, -1000), audio.samples)
    }

    // --- sliceMs ----------------------------------------------------------------------------

    /** At 1000 Hz one sample is exactly 1 ms, so timestamps map trivially to indices. */
    private val hz = 1_000
    private val ramp = ShortArray(10) { it.toShort() }

    @Test
    fun sliceFullRangeReturnsEverything() {
        assertArrayEquals(ramp, sliceMs(ramp, hz, 0L, 10L))
    }

    @Test
    fun slicePartialWindowUsesHalfOpenRange() {
        assertArrayEquals(shortArrayOf(2, 3, 4), sliceMs(ramp, hz, 2L, 5L))
    }

    @Test
    fun sliceClampsEndBeyondAudio() {
        assertArrayEquals(shortArrayOf(7, 8, 9), sliceMs(ramp, hz, 7L, 999L))
    }

    @Test
    fun sliceStartBeyondAudioYieldsEmpty() {
        assertEquals(0, sliceMs(ramp, hz, 50L, 60L).size)
    }

    @Test
    fun sliceNonAdvancingOrEmptyWindowYieldsEmpty() {
        assertEquals(0, sliceMs(ramp, hz, 5L, 5L).size)
        assertEquals(0, sliceMs(ramp, hz, 5L, 1L).size)
        assertEquals(0, sliceMs(ShortArray(0), hz, 0L, 10L).size)
        assertEquals(0, sliceMs(ramp, 0, 0L, 10L).size)
    }

    @Test
    fun sliceClampsNegativeStartToZero() {
        assertArrayEquals(shortArrayOf(0, 1, 2), sliceMs(ramp, hz, -5L, 3L))
    }

    @Test
    fun sliceWithExtremeTimestampsDoesNotOverflow() {
        assertTrue(sliceMs(ramp, hz, Long.MAX_VALUE / 2, Long.MAX_VALUE).isEmpty())
    }
}
