package com.andharness.ru.bg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure [WavHeader] builder (BUG-020). The header must always describe exactly the
 * payload the capture wrote: `BlockAlign`/`ByteRate` derived from the channel count, and
 * `ChunkSize`/`Subchunk2Size` derived from the real data byte count (little-endian RIFF fields).
 *
 * References:
 * - Canonical WAVE format: http://soundfile.sapp.org/doc/WaveFormat/
 * - WAVE media type / little-endian RIFF: https://www.rfc-editor.org/rfc/rfc2361
 */
class WavHeaderTest {

    @Test
    fun buildsExactCanonicalHeaderForStereoPcm16() {
        val header = WavHeader.build(
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 16,
            dataByteCount = 4,
        )

        assertEquals(44, WavHeader.SIZE)
        assertEquals(44, header.size)
        val expected = hexBytes(
            "52 49 46 46 28 00 00 00 57 41 56 45 66 6D 74 20 " +
                "10 00 00 00 01 00 02 00 44 AC 00 00 10 B1 02 00 " +
                "04 00 10 00 64 61 74 61 04 00 00 00",
        )
        assertArrayEquals(expected, header)
    }

    @Test
    fun headerMatchesStereoFiveSecondPayloadFromBug020() {
        // BUG-020: 5 s of 44.1 kHz stereo PCM16 => 44100 * 5 * 2 channels * 2 bytes.
        val dataByteCount = 882_000
        val header = WavHeader.build(
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 16,
            dataByteCount = dataByteCount,
        )

        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(16, le32(header, 16))
        assertEquals(1, le16(header, 20))
        assertEquals(2, le16(header, 22))
        assertEquals(44_100, le32(header, 24))
        assertEquals(176_400, le32(header, 28))
        assertEquals(4, le16(header, 32))
        assertEquals(16, le16(header, 34))
        assertEquals(dataByteCount, le32(header, 40))
        assertEquals(36 + dataByteCount, le32(header, 4))
    }

    @Test
    fun headerDeclaresMonoWhenRecordingMono() {
        // 1 s of 8 kHz mono PCM16 => 8000 * 2 bytes.
        val dataByteCount = 16_000
        val header = WavHeader.build(
            sampleRate = 8000,
            channels = 1,
            bitsPerSample = 16,
            dataByteCount = dataByteCount,
        )

        assertEquals(1, le16(header, 22))
        assertEquals(2, le16(header, 32))
        assertEquals(16_000, le32(header, 28))
        assertEquals(dataByteCount, le32(header, 40))
        assertEquals(36 + dataByteCount, le32(header, 4))
    }

    private fun hexBytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

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
