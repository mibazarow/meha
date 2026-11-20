package com.andharness.ru.data.communications.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [Fbank] frontend — the fbank half of the WeSpeaker speaker-embedding
 * pipeline. No Android, no model, no network: only arithmetic on [ShortArray].
 */
class FbankTest {

    private val sampleRate = 16_000
    private val frameLength = 400 // 25 ms @ 16 kHz
    private val frameShift = 160 // 10 ms @ 16 kHz

    /** Deterministic non-trivial signal: a 440 Hz tone plus a small DC bias. */
    private fun tone(samples: Int, freqHz: Double = 440.0, dc: Double = 100.0): ShortArray =
        ShortArray(samples) { i ->
            (dc + 8_000.0 * sin(2.0 * PI * freqHz * i / sampleRate)).toInt()
                .coerceIn(-32_768, 32_767)
                .toShort()
        }

    // --- frame counting ----------------------------------------------------------------------

    @Test
    fun frameCountForKnownLengths() {
        assertEquals(98, Fbank.frameCount(16_000, frameLength, frameShift))
        assertEquals(1, Fbank.frameCount(400, frameLength, frameShift))
        assertEquals(2, Fbank.frameCount(560, frameLength, frameShift))
        assertEquals(3, Fbank.frameCount(800, frameLength, frameShift))
    }

    @Test
    fun frameCountShorterThanOneFrameIsZero() {
        assertEquals(0, Fbank.frameCount(0, frameLength, frameShift))
        assertEquals(0, Fbank.frameCount(1, frameLength, frameShift))
        assertEquals(0, Fbank.frameCount(399, frameLength, frameShift))
    }

    @Test
    fun frameCountRejectsNonPositiveGeometry() {
        assertEquals(0, Fbank.frameCount(16_000, 0, frameShift))
        assertEquals(0, Fbank.frameCount(16_000, frameLength, 0))
    }

    // --- shape / bin count -------------------------------------------------------------------

    @Test
    fun computeReturnsRequestedBinCountPerFrame() {
        val features = Fbank.compute(tone(16_000), sampleRate)
        assertEquals(98, features.size)
        features.forEach { assertEquals(Fbank.DEFAULT_NUM_BINS, it.size) }
    }

    @Test
    fun computeHonoursCustomBinCount() {
        val features = Fbank.compute(tone(8_000), sampleRate, numBins = 40)
        assertEquals(Fbank.frameCount(8_000, frameLength, frameShift), features.size)
        features.forEach { assertEquals(40, it.size) }
    }

    @Test
    fun computeFrameCountMatchesFormula() {
        for (numSamples in intArrayOf(400, 1_000, 16_000, 23_456)) {
            val features = Fbank.compute(tone(numSamples), sampleRate)
            assertEquals(
                "numSamples=$numSamples",
                Fbank.frameCount(numSamples, frameLength, frameShift),
                features.size,
            )
        }
    }

    // --- guards ------------------------------------------------------------------------------

    @Test
    fun emptyInputReturnsEmpty() {
        assertTrue(Fbank.compute(ShortArray(0), sampleRate).isEmpty())
    }

    @Test
    fun tooShortInputReturnsEmpty() {
        assertTrue(Fbank.compute(tone(399), sampleRate).isEmpty())
    }

    @Test
    fun invalidSampleRateOrBinsReturnsEmpty() {
        assertTrue(Fbank.compute(tone(16_000), sampleRate = 0).isEmpty())
        assertTrue(Fbank.compute(tone(16_000), sampleRate, numBins = 0).isEmpty())
        assertTrue(Fbank.compute(tone(16_000), sampleRate, frameShiftMs = 0).isEmpty())
    }

    // --- numerical health --------------------------------------------------------------------

    @Test
    fun digitalSilenceProducesFiniteValues() {
        val features = Fbank.compute(ShortArray(16_000), sampleRate, applyCmn = true)
        assertEquals(98, features.size)
        features.forEach { frame ->
            frame.forEach { value -> assertTrue("silence value must be finite: $value", value.isFinite()) }
        }
    }

    @Test
    fun toneProducesFiniteValues() {
        val features = Fbank.compute(tone(16_000), sampleRate, applyCmn = true)
        features.forEach { frame ->
            frame.forEach { value -> assertTrue("tone value must be finite: $value", value.isFinite()) }
        }
    }

    // --- CMN ---------------------------------------------------------------------------------

    @Test
    fun cmnReducesPerBinMeansTowardZero() {
        val raw = Fbank.compute(tone(16_000), sampleRate, applyCmn = false)
        val normalised = Fbank.cmn(raw)

        for (bin in 0 until Fbank.DEFAULT_NUM_BINS) {
            var sum = 0.0
            for (frame in normalised) sum += frame[bin]
            val mean = sum / normalised.size
            assertTrue("bin $bin mean after CMN is $mean", abs(mean) < 1e-4)
        }
    }

    @Test
    fun applyCmnMatchesExplicitCmn() {
        val samples = tone(16_000)
        val viaFlag = Fbank.compute(samples, sampleRate, applyCmn = true)
        val explicit = Fbank.cmn(Fbank.compute(samples, sampleRate, applyCmn = false))

        assertEquals(viaFlag.size, explicit.size)
        for (f in viaFlag.indices) {
            assertArrayEquals("frame $f", explicit[f], viaFlag[f], 1e-6f)
        }
    }

    @Test
    fun cmnOfEmptyIsEmpty() {
        assertTrue(Fbank.cmn(emptyArray()).isEmpty())
    }

    // --- determinism -------------------------------------------------------------------------

    @Test
    fun computeIsDeterministic() {
        val samples = tone(16_000)
        val first = Fbank.compute(samples, sampleRate, applyCmn = true)
        val second = Fbank.compute(samples, sampleRate, applyCmn = true)

        assertEquals(first.size, second.size)
        for (f in first.indices) {
            assertArrayEquals(first[f], second[f], 0f)
        }
    }
}
