package com.andharness.ru.data.communications.audio

import kotlin.math.roundToInt

/**
 * Pure (Android-free) PCM helpers for the audio-decode pipeline that feeds `whisper-cli`.
 *
 * The pipeline standardizes any container/codec into **16 kHz, mono, 16-bit PCM** so the bundled
 * `whisper-cli` never has to decode exotic containers itself (Android's `MediaCodec` does that):
 *
 *     decoded interleaved ShortArray ──[downmixToMono]──> mono ──[resample]──> 16 kHz mono
 *
 * Everything here is plain arithmetic on `ShortArray`, so it is unit-testable on the JVM. Integers
 * are interpreted as signed 16-bit PCM samples in `[-32768, 32767]`.
 */
object PcmAudio {

    /** Sample rate expected by `whisper-cli` (Whisper models are trained on 16 kHz audio). */
    const val TARGET_SAMPLE_RATE = 16_000

    /**
     * Downmixes [samples] (interleaved frames of [channels] channels) to a single channel by
     * averaging the channels of every frame, then clamping to the signed-16-bit range.
     *
     * - [channels] `== 1` returns a defensive copy (identity).
     * - **Odd-length guard:** a trailing partial frame (when `samples.size` is not a multiple of
     *   [channels]) is dropped, because it cannot be averaged.
     * - Averaging uses integer division, so it truncates toward zero (a slow, inaudible DC bias;
     *   irrelevant for speech recognition).
     *
     * @throws IllegalArgumentException when [channels] is not positive.
     */
    fun downmixToMono(samples: ShortArray, channels: Int): ShortArray {
        require(channels >= 1) { "channels must be >= 1: $channels" }
        if (channels == 1) return samples.copyOf()

        val frames = samples.size / channels
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            val base = frame * channels
            var sum = 0
            for (channel in 0 until channels) sum += samples[base + channel].toInt()
            out[frame] = (sum / channels).coerceIn(SAMPLE_MIN, SAMPLE_MAX).toShort()
        }
        return out
    }

    /**
     * Resamples [samples] from [fromRate] to [toRate] using **linear interpolation**.
     *
     * Method: output sample `i` corresponds to source position `i * fromRate / toRate`; the two
     * neighbouring source samples are linearly blended by the fractional part. The output length is
     * `round(samples.size * toRate / fromRate)`, so the duration is preserved to within one sample.
     * Equal rates return a defensive copy, and empty input returns an empty array.
     *
     * Honest limitations (acceptable for ASR, not for hi-fi):
     * - Linear interpolation is a first-order resampler: it has no anti-aliasing low-pass filter, so
     *   downsampling 44.1 kHz → 16 kHz can alias energy above ~8 kHz. Whisper is trained on 16 kHz
     *   speech and tolerates this.
     * - Resampling is stateless per call: resampling independent chunks can introduce tiny
     *   discontinuities at chunk boundaries and a sub-millisecond length drift. The decoder resamples
     *   per decoded buffer, so this is the intended, documented behaviour.
     *
     * @throws IllegalArgumentException when either rate is not positive.
     */
    fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0) { "fromRate must be positive: $fromRate" }
        require(toRate > 0) { "toRate must be positive: $toRate" }
        if (samples.isEmpty()) return ShortArray(0)
        if (fromRate == toRate) return samples.copyOf()

        val outLength = (samples.size.toDouble() * toRate / fromRate).roundToInt()
        val out = ShortArray(outLength)
        val step = fromRate.toDouble() / toRate
        val lastIndex = samples.size - 1
        for (i in 0 until outLength) {
            val position = i * step
            val index = position.toInt().coerceIn(0, lastIndex)
            val frac = position - index
            val a = samples[index].toDouble()
            val b = samples[(index + 1).coerceAtMost(lastIndex)].toDouble()
            val value = a + (b - a) * frac
            out[i] = value.roundToInt().coerceIn(SAMPLE_MIN, SAMPLE_MAX).toShort()
        }
        return out
    }

    /**
     * Full convert plan for a decoded interleaved buffer: downmix to mono, then resample to
     * [TARGET_SAMPLE_RATE]. Returns the 16 kHz mono samples ready to be written as PCM16 WAV.
     */
    fun toMono16k(samples: ShortArray, channels: Int, fromRate: Int): ShortArray =
        resample(downmixToMono(samples, channels), fromRate, TARGET_SAMPLE_RATE)

    private const val SAMPLE_MIN = -32_768
    private const val SAMPLE_MAX = 32_767
}
