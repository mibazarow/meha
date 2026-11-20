package com.andharness.ru.domain.agent

/**
 * Rolling calibration of the local prompt-size ESTIMATE against the size the provider actually
 * billed (`usage.prompt_tokens`), CONTEXT_TASKS_V2 §«счётчик расхождения».
 *
 * The estimator's constants (`chars/4` ASCII, `chars/2` Cyrillic, `chars/1.5` other, plus per-message
 * and per-tool overheads) are plausible but never measured. This keeps the last [windowSize]
 * `estimate / actual` ratios so a divergence above [DIVERGENCE_THRESHOLD] becomes visible in the
 * debug log and can be corrected from evidence rather than from intuition.
 *
 * Pure and Android-free. Only the prompt (input) side is calibrated: the estimate is used to decide
 * whether a request fits, and only the prompt determines that.
 */
class PromptTokenCalibration(private val windowSize: Int = DEFAULT_WINDOW) {

    private val ratios = ArrayDeque<Double>()

    /**
     * Records one request and returns its ratio (`estimated / actual`), or `null` when either side
     * is unknown (no provider usage, or an empty estimate) — an unknown ratio is not a zero ratio.
     */
    fun record(estimated: Long, actual: Long): Double? {
        if (estimated <= 0L || actual <= 0L) return null
        val ratio = estimated.toDouble() / actual.toDouble()
        ratios.addLast(ratio)
        while (ratios.size > windowSize) ratios.removeFirst()
        return ratio
    }

    /** Mean of the recorded ratios, or `null` before the first sample. */
    fun averageRatio(): Double? = if (ratios.isEmpty()) null else ratios.average()

    /** True when the window is full enough and the mean is off by more than [DIVERGENCE_THRESHOLD]. */
    fun diverges(): Boolean {
        val average = averageRatio() ?: return false
        return kotlin.math.abs(1.0 - average) > DIVERGENCE_THRESHOLD
    }

    /** Recorded ratios, oldest first (for tests and the debug log). */
    fun samples(): List<Double> = ratios.toList()

    companion object {
        /** How many recent requests the average covers. */
        const val DEFAULT_WINDOW = 20

        /** Relative divergence (15%) at which the constants should be revisited. */
        const val DIVERGENCE_THRESHOLD = 0.15
    }
}
