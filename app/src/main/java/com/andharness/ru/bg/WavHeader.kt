package com.andharness.ru.bg

/**
 * Pure (Android-free) builder for the 44-byte canonical PCM WAV (RIFF/WAVE) header.
 *
 * Layout (all multi-byte fields little-endian, as RIFF requires):
 * - `RIFF` + ChunkSize (`36 + Subchunk2Size`) + `WAVE`
 * - `fmt ` + Subchunk1Size(16) + AudioFormat(1=PCM) + NumChannels + SampleRate + ByteRate +
 *   BlockAlign + BitsPerSample
 * - `data` + Subchunk2Size (the actual payload byte count)
 *
 * Sizes are derived from [dataByteCount] alone, so the header can never disagree with the payload:
 * `BlockAlign = channels * bitsPerSample / 8`, `ByteRate = sampleRate * BlockAlign`,
 * `ChunkSize = 36 + dataByteCount`, `Subchunk2Size = dataByteCount`.
 *
 * References:
 * - Canonical WAVE format: http://soundfile.sapp.org/doc/WaveFormat/
 * - WAVE media type / little-endian RIFF: https://www.rfc-editor.org/rfc/rfc2361
 */
object WavHeader {

    /** Size in bytes of the canonical PCM WAV header. */
    const val SIZE = 44

    fun build(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataByteCount: Int,
    ): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels > 0) { "channels must be positive: $channels" }
        require(bitsPerSample > 0 && bitsPerSample % 8 == 0) {
            "bitsPerSample must be a positive multiple of 8: $bitsPerSample"
        }
        require(dataByteCount >= 0) { "dataByteCount must not be negative: $dataByteCount" }

        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlign

        val out = ByteArray(SIZE)
        var pos = 0

        fun ascii(text: String) {
            for (c in text) out[pos++] = c.code.toByte()
        }

        fun le32(value: Int) {
            out[pos++] = (value and 0xFF).toByte()
            out[pos++] = ((value ushr 8) and 0xFF).toByte()
            out[pos++] = ((value ushr 16) and 0xFF).toByte()
            out[pos++] = ((value ushr 24) and 0xFF).toByte()
        }

        fun le16(value: Int) {
            out[pos++] = (value and 0xFF).toByte()
            out[pos++] = ((value ushr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(36 + dataByteCount)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1)
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(bitsPerSample)
        ascii("data")
        le32(dataByteCount)
        return out
    }
}
