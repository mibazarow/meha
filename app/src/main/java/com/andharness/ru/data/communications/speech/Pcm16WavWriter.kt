package com.andharness.ru.data.communications.speech

import com.andharness.ru.bg.WavHeader

/**
 * Pure (Android-free) assembly of a canonical 16 kHz mono PCM16 WAV from raw little-endian PCM
 * bytes: the 44-byte [WavHeader] immediately followed by the payload. Sizes are derived from
 * `pcm.size` alone, so the header can never disagree with the bytes it describes.
 *
 * Extracted from [MicWavRecorder] so the byte layout is unit-testable on the JVM without an
 * emulator. References:
 * - Canonical WAVE format: http://soundfile.sapp.org/doc/WaveFormat/
 * - WAVE media type / little-endian RIFF: https://www.rfc-editor.org/rfc/rfc2361
 */
object Pcm16WavWriter {

    /** Sample rate whisper.cpp expects: 16 kHz. */
    const val DEFAULT_SAMPLE_RATE = 16_000

    /** Mono, matching the microphone recorder's `CHANNEL_IN_MONO` capture. */
    const val DEFAULT_CHANNELS = 1

    /** PCM16: two little-endian bytes per sample. */
    const val BITS_PER_SAMPLE = 16

    /**
     * Builds a complete WAV file: `[44-byte header][pcm]`. [pcm] must already be little-endian
     * PCM16 samples; [sampleRate] and [channels] must describe the captured stream.
     */
    fun build(
        pcm: ByteArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        channels: Int = DEFAULT_CHANNELS,
    ): ByteArray {
        val header = WavHeader.build(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = BITS_PER_SAMPLE,
            dataByteCount = pcm.size,
        )
        return header + pcm
    }
}
