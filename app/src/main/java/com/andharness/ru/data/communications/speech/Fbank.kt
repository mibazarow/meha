package com.andharness.ru.data.communications.speech

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Pure-JVM Kaldi-style log-mel filterbank ("fbank") frontend for the WeSpeaker speaker-embedding
 * model ([SpeechModel.SPEAKER_EMB]). No Android dependency, so it is unit-testable on the JVM.
 *
 * The parameters mirror the model's `preprocessor_config.json` (onnx-community
 * `wespeaker-voxceleb-resnet34-LM`): 16 kHz, mono, 80 mel bins, 25 ms frames, 10 ms shift, Hamming
 * window, no dither, `use_energy: false`, `snip_edges: true`. The upstream WeSpeaker ONNX export
 * expects **pre-computed** fbank features (`[B, T, 80]`), and the reference `wespeakerruntime`
 * applies CMN (per-utterance mean normalisation) with `cmn=True` before inference, which [compute]
 * does when [applyCmn] is set — [SpeakerEmbedder] calls it that way.
 *
 * Pipeline per frame: DC-offset removal -> pre-emphasis (0.97) -> Hamming window -> FFT (radix-2,
 * zero-padded to the next power of two) -> power spectrum -> triangular mel filterbank (HTK mel
 * scale, `1127 * ln(1 + f/700)`) -> natural log.
 *
 * **Honesty note:** this is a straightforward, self-contained radix-2 FFT fbank, NOT a bit-exact
 * reimplementation of Kaldi's `compute-fbank-feats`. Notable, deliberate divergences: Kaldi uses a
 * split-radix real FFT and its own bin/power convention; the mel filterbank below is built directly
 * on the one-sided power spectrum `[0 .. N/2]`. Speaker embeddings are compared by cosine
 * similarity in a 256-dim space, which is tolerant of these small numerical differences (the
 * re-export used for on-device diarisation makes the same trade-off). Do not use this as a drop-in
 * for a Kaldi feature-compatibility test.
 *
 * References:
 * - Model card / preprocessor_config.json —
 *   https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM
 * - WeSpeaker runtime (fbank + `cmn=True` default) — https://pypi.org/project/wespeakerruntime/
 * - Kaldi `compute-fbank-feats` / `MelBanks` (HTK mel scale, pre-emphasis, dither, snip-edges) —
 *   https://kaldi-asr.org/doc/feat.html
 */
object Fbank {

    /** Mel bins produced by default (matches the model's `num_mel_bins`). */
    const val DEFAULT_NUM_BINS = 80

    /** Frame length in milliseconds (matches the model's `frame_length`). */
    const val DEFAULT_FRAME_LENGTH_MS = 25

    /** Frame shift in milliseconds (matches the model's `frame_shift`). */
    const val DEFAULT_FRAME_SHIFT_MS = 10

    /** Pre-emphasis coefficient (Kaldi's default). */
    const val DEFAULT_PRE_EMPHASIS = 0.97f

    /** Mel filterbank low cutoff in Hz (Kaldi's default `--low-freq=20`). */
    const val DEFAULT_LOW_FREQ_HZ = 20f

    /** High cutoff in Hz; a non-positive value means the Nyquist frequency (Kaldi `--high-freq=0`). */
    const val DEFAULT_HIGH_FREQ_HZ = 0f

    /** Energy floor before `ln`, so digital silence yields a finite (very negative) value. */
    private const val LOG_FLOOR = 1e-10f

    /** HTK mel scale constant: `mel = 1127 * ln(1 + f/700)`. */
    private const val MEL_HTK = 1127.0

    /** HTK mel scale reference frequency. */
    private const val MEL_F0 = 700.0

    /**
     * Number of frames `snip_edges=true` produces for [numSamples] samples with [frameLength] and
     * [frameShift] (in samples): `1 + (numSamples - frameLength) / frameShift`, or `0` when the
     * input is shorter than one frame. Every frame lies fully inside the signal (no padding).
     */
    fun frameCount(numSamples: Int, frameLength: Int, frameShift: Int): Int {
        if (frameLength <= 0 || frameShift <= 0 || numSamples < frameLength) return 0
        return 1 + (numSamples - frameLength) / frameShift
    }

    /**
     * Computes the log-mel filterbank for [samples] (signed 16-bit PCM, mono, [sampleRate] Hz).
     * Returns `frames x numBins` values, where the frame count follows [frameCount] with
     * `snip_edges=true`. Empty or too-short input returns an empty array (never throws).
     *
     * [applyCmn] subtracts the per-bin mean over all frames (per-utterance CMN) before returning;
     * pass `false` to inspect the raw log-mel, then call [cmn] explicitly.
     */
    fun compute(
        samples: ShortArray,
        sampleRate: Int,
        numBins: Int = DEFAULT_NUM_BINS,
        frameLengthMs: Int = DEFAULT_FRAME_LENGTH_MS,
        frameShiftMs: Int = DEFAULT_FRAME_SHIFT_MS,
        lowFreqHz: Float = DEFAULT_LOW_FREQ_HZ,
        highFreqHz: Float = DEFAULT_HIGH_FREQ_HZ,
        preEmphasis: Float = DEFAULT_PRE_EMPHASIS,
        removeDcOffset: Boolean = true,
        applyCmn: Boolean = false,
    ): Array<FloatArray> {
        if (samples.isEmpty() || sampleRate <= 0 || numBins <= 0 ||
            frameLengthMs <= 0 || frameShiftMs <= 0
        ) {
            return emptyArray()
        }
        val frameLength = sampleRate * frameLengthMs / 1000
        val frameShift = sampleRate * frameShiftMs / 1000
        if (frameLength <= 0 || frameShift <= 0) return emptyArray()

        val numFrames = frameCount(samples.size, frameLength, frameShift)
        if (numFrames <= 0) return emptyArray()

        val fftSize = nextPowerOfTwo(frameLength)
        val window = hamming(frameLength)
        val filters = melFilterBank(numBins, fftSize, sampleRate, lowFreqHz, highFreqHz)
        val numPowerBins = fftSize / 2 + 1

        val frames = Array(numFrames) { FloatArray(numBins) }
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val frame = FloatArray(frameLength)

        for (f in 0 until numFrames) {
            val start = f * frameShift
            for (i in 0 until frameLength) frame[i] = samples[start + i].toFloat()

            if (removeDcOffset) removeDcOffset(frame)
            preEmphasize(frame, preEmphasis)
            for (i in 0 until frameLength) frame[i] *= window[i]

            re.fill(0f)
            im.fill(0f)
            for (i in 0 until frameLength) re[i] = frame[i]
            fft(re, im)

            for (bin in 0 until numBins) {
                val filter = filters[bin]
                var energy = 0f
                for (k in filter.startBin until filter.startBin + filter.weights.size) {
                    if (k >= numPowerBins) break
                    val power = re[k] * re[k] + im[k] * im[k]
                    energy += filter.weights[k - filter.startBin] * power
                }
                frames[f][bin] = ln(energy.coerceAtLeast(LOG_FLOOR))
            }
        }
        return if (applyCmn) cmn(frames) else frames
    }

    /**
     * Per-utterance cepstral mean normalisation: subtracts the mean over frames from every mel bin,
     * so a constant channel/gain bias cancels. Returns [features] unchanged when it is empty or the
     * frames are empty.
     */
    fun cmn(features: Array<FloatArray>): Array<FloatArray> {
        if (features.isEmpty()) return features
        val bins = features[0].size
        if (bins == 0) return features
        val sums = DoubleArray(bins)
        for (frame in features) {
            if (frame.size != bins) return features
            for (b in 0 until bins) sums[b] += frame[b]
        }
        val count = features.size.toDouble()
        val means = FloatArray(bins) { (sums[it] / count).toFloat() }
        val out = Array(features.size) { FloatArray(bins) }
        for (f in features.indices) {
            for (b in 0 until bins) out[f][b] = features[f][b] - means[b]
        }
        return out
    }

    /** Subtracts the frame mean (Kaldi `--remove-dc-offset=true`). */
    private fun removeDcOffset(frame: FloatArray) {
        var sum = 0.0
        for (value in frame) sum += value
        val mean = (sum / frame.size).toFloat()
        for (i in frame.indices) frame[i] -= mean
    }

    /** Applies `y[i] = x[i] - coeff * x[i-1]` for `i > 0`; the first sample is left unchanged. */
    private fun preEmphasize(frame: FloatArray, coeff: Float) {
        if (coeff == 0f) return
        for (i in frame.size - 1 downTo 1) frame[i] -= coeff * frame[i - 1]
    }

    /** Periodic Hamming window `0.54 - 0.46 * cos(2*pi*n/(N-1))`. */
    private fun hamming(n: Int): FloatArray {
        if (n <= 1) return FloatArray(n) { 1f }
        return FloatArray(n) { i -> (0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))).toFloat() }
    }

    /** A triangular mel filter as a contiguous run of spectrum bins starting at [startBin]. */
    private class MelFilter(val startBin: Int, val weights: FloatArray)

    /**
     * Triangular mel filterbank over the one-sided power spectrum: [numBins] filters, uniformly
     * spaced in mel between [lowFreqHz] and [highFreqHz] (a non-positive high cutoff means Nyquist),
     * using the HTK mel scale. Weights are the raw triangle values (no per-filter energy
     * normalisation), matching Kaldi's default `MelBanks`.
     */
    private fun melFilterBank(
        numBins: Int,
        fftSize: Int,
        sampleRate: Int,
        lowFreqHz: Float,
        highFreqHz: Float,
    ): Array<MelFilter> {
        val numPowerBins = fftSize / 2 + 1
        val nyquist = sampleRate / 2f
        val low = lowFreqHz.coerceIn(0f, nyquist)
        val high = if (highFreqHz <= 0f || highFreqHz > nyquist) nyquist else highFreqHz
        val melLow = hertzToMel(low)
        val melHigh = hertzToMel(high)

        val binPoints = FloatArray(numBins + 2) { i ->
            val mel = melLow + (melHigh - melLow) * i / (numBins + 1)
            melToHertz(mel) * fftSize / sampleRate
        }

        return Array(numBins) { b ->
            val left = binPoints[b]
            val center = binPoints[b + 1]
            val right = binPoints[b + 2]
            val start = ceil(left).toInt().coerceIn(0, numPowerBins - 1)
            val end = floor(right).toInt().coerceIn(0, numPowerBins - 1)
            if (end < start) {
                MelFilter(start, FloatArray(0))
            } else {
                val weights = FloatArray(end - start + 1)
                for (k in start..end) {
                    val x = k.toFloat()
                    weights[k - start] = when {
                        x < center -> if (center > left) (x - left) / (center - left) else 0f
                        x > center -> if (right > center) (right - x) / (right - center) else 0f
                        else -> 1f
                    }
                }
                MelFilter(start, weights)
            }
        }
    }

    private fun hertzToMel(hz: Float): Float = (MEL_HTK * ln(1.0 + hz / MEL_F0)).toFloat()

    private fun melToHertz(mel: Float): Float = (MEL_F0 * (exp(mel / MEL_HTK) - 1.0)).toFloat()

    /** Smallest power of two `>= n` (at least 1). */
    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /**
     * In-place iterative radix-2 Cooley-Tukey FFT. [re]/[im] must have the same power-of-two length;
     * standard bit-reversal permutation followed by log2(N) butterfly stages. Uses double-precision
     * twiddle accumulation for numerical stability.
     */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n <= 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var curR = 1.0
                var curI = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vr = (re[b] * curR - im[b] * curI).toFloat()
                    val vi = (re[b] * curI + im[b] * curR).toFloat()
                    val ur = re[a]
                    val ui = im[a]
                    re[a] = ur + vr
                    im[a] = ui + vi
                    re[b] = ur - vr
                    im[b] = ui - vi
                    val nextR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nextR
                }
                i += length
            }
            length = length shl 1
        }
    }
}
