package com.andharness.ru.data.communications.audio

import com.andharness.ru.bg.WavHeader
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PcmAudio] and the [WavHeader]-based 16 kHz mono PCM16 output contract.
 * Pure arithmetic + byte layout only — no Android runtime required.
 */
class PcmAudioTest {

    // ---------------------------------------------------------------- downmix

    @Test
    fun downmixStereoAveragesChannels() {
        val stereo = shortArrayOf(100, 200, 300, 400)
        assertArrayEquals(shortArrayOf(150, 350), PcmAudio.downmixToMono(stereo, 2))
    }

    @Test
    fun downmixMonoReturnsIdentityCopy() {
        val mono = shortArrayOf(1, -2, 3)
        val result = PcmAudio.downmixToMono(mono, 1)
        assertArrayEquals(mono, result)
        assertNotSame(mono, result)
    }

    @Test
    fun downmixDropsTrailingPartialFrame() {
        // 5 samples / 2 channels -> 2 complete frames; the odd trailing sample is dropped.
        val odd = shortArrayOf(100, 200, 300, 400, 500)
        assertArrayEquals(shortArrayOf(150, 350), PcmAudio.downmixToMono(odd, 2))
    }

    @Test
    fun downmixClampsAtBoundariesWithoutWrapping() {
        val extremes = shortArrayOf(32767, 32767, -32768, -32768)
        assertArrayEquals(shortArrayOf(32767, -32768), PcmAudio.downmixToMono(extremes, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun downmixRejectsNonPositiveChannels() {
        PcmAudio.downmixToMono(shortArrayOf(1, 2), 0)
    }

    // --------------------------------------------------------------- resample

    @Test
    fun resampleIdentityWhenRatesEqual() {
        val input = shortArrayOf(10, -20, 30, -40)
        val result = PcmAudio.resample(input, 16_000, 16_000)
        assertArrayEquals(input, result)
        assertNotSame(input, result)
    }

    @Test
    fun resampleEmptyInputReturnsEmpty() {
        assertTrue(PcmAudio.resample(ShortArray(0), 44_100, 16_000).isEmpty())
    }

    @Test
    fun resample44100To16000LengthMatchesRounding() {
        val input = ShortArray(44_100)
        val result = PcmAudio.resample(input, 44_100, 16_000)
        assertEquals(16_000, result.size)
        assertEquals((44_100.0 * 16_000 / 44_100).roundToInt(), result.size)
    }

    @Test
    fun resampleOutputLengthMatchesRoundingForVariedInputs() {
        for (n in intArrayOf(1, 7, 100, 1_024, 44_100, 48_000, 12_345)) {
            val expected = (n.toDouble() * 16_000 / 44_100).roundToInt()
            val actual = PcmAudio.resample(ShortArray(n), 44_100, 16_000).size
            assertEquals("n=$n", expected, actual)
        }
    }

    @Test
    fun resampleConstantSignalStaysConstantAndInRange() {
        val input = ShortArray(1_000) { 12_345 }
        val result = PcmAudio.resample(input, 48_000, 16_000)
        assertTrue(result.isNotEmpty())
        result.forEach { assertEquals(12_345, it.toInt()) }
    }

    @Test
    fun resampleUpsamplingStaysWithinSigned16BitRange() {
        val input = shortArrayOf(32_767, -32_768, 32_767, -32_768)
        val result = PcmAudio.resample(input, 8_000, 16_000)
        assertTrue(result.isNotEmpty())
        result.forEach {
            assertTrue(it >= Short.MIN_VALUE && it <= Short.MAX_VALUE)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun resampleRejectsNonPositiveRates() {
        PcmAudio.resample(shortArrayOf(1, 2), 0, 16_000)
    }

    // ------------------------------------------------------------ convert plan

    @Test
    fun toMono16kCombinesDownmixAndResample() {
        // Stereo 32 kHz -> mono 16 kHz halves the frame count.
        val stereo = shortArrayOf(1_000, 1_000, 2_000, 2_000)
        val result = PcmAudio.toMono16k(stereo, channels = 2, fromRate = 32_000)
        assertEquals(1, result.size)
        assertEquals(1_000, result[0].toInt())
    }

    // ------------------------------------------------------------- WAV output

    @Test
    fun wavHeaderHasCanonicalRiffWave16kMonoFields() {
        val dataByteCount = 3_200 // 100 ms of 16 kHz mono PCM16
        val header = WavHeader.build(
            sampleRate = 16_000,
            channels = 1,
            bitsPerSample = 16,
            dataByteCount = dataByteCount,
        )

        assertEquals(WavHeader.SIZE, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals(36L + dataByteCount, u32(header, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals(16, u32(header, 16)) // PCM fmt chunk size
        assertEquals(1, u16(header, 20)) // AudioFormat = PCM
        assertEquals(1, u16(header, 22)) // NumChannels = mono
        assertEquals(16_000, u32(header, 24)) // SampleRate
        assertEquals(32_000, u32(header, 28)) // ByteRate = rate * channels * bytes
        assertEquals(2, u16(header, 32)) // BlockAlign
        assertEquals(16, u16(header, 34)) // BitsPerSample
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(dataByteCount.toLong(), u32(header, 40))
    }

    @Test
    fun wavFileLengthIsHeaderPlusPayload() {
        val payload = ByteArray(640)
        val header = WavHeader.build(16_000, 1, 16, payload.size)
        val wav = header + payload
        assertEquals(WavHeader.SIZE + payload.size, wav.size)
        assertEquals((WavHeader.SIZE + payload.size - 8).toLong(), u32(wav, 4))
        assertEquals(payload.size.toLong(), u32(wav, 40))
    }

    // ----------------------------------------------------------------- helpers

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
