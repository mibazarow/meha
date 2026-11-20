package com.andharness.ru.data.communications.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure [Pcm16WavWriter]: the produced file must be exactly the 44-byte canonical
 * RIFF/WAVE header followed by the PCM payload, with every size field derived from the payload.
 * References:
 * - Canonical WAVE format: http://soundfile.sapp.org/doc/WaveFormat/
 * - WAVE media type / little-endian RIFF: https://www.rfc-editor.org/rfc/rfc2361
 */
class Pcm16WavWriterTest {

    @Test
    fun writesHeaderThenPayloadWithExactLength() {
        val pcm = ByteArray(320) { (it % 256).toByte() } // 10 ms of 16 kHz mono PCM16

        val wav = Pcm16WavWriter.build(pcm)

        assertEquals(44 + pcm.size, wav.size)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun headerDescribes16kMonoPcm16() {
        val pcmSize = 32_000 // 1 s of 16 kHz mono PCM16
        val wav = Pcm16WavWriter.build(ByteArray(pcmSize))

        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals(16, le32(wav, 16)) // PCM subchunk size
        assertEquals(1, le16(wav, 20)) // AudioFormat = PCM
        assertEquals(1, le16(wav, 22)) // mono
        assertEquals(16_000, le32(wav, 24)) // sample rate
        assertEquals(32_000, le32(wav, 28)) // byte rate = 16000 * 1 * 2
        assertEquals(2, le16(wav, 32)) // block align
        assertEquals(16, le16(wav, 34)) // bits per sample
        assertEquals(pcmSize, le32(wav, 40))
        assertEquals(36 + pcmSize, le32(wav, 4))
    }

    @Test
    fun emptyPayloadStillProducesAValidHeader() {
        val wav = Pcm16WavWriter.build(ByteArray(0))

        assertEquals(44, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals(0, le32(wav, 40))
        assertEquals(36, le32(wav, 4))
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
