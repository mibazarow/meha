package com.andharness.ru.domain.agent

/**
 * Progress-aware anti-loop guard for the agent tool loop (BUG-045).
 *
 * The old guard compared only the tool-call batch signature and aborted after a fixed number of
 * identical batches. That killed legitimate repetition whose RESULT changes — blink the flashlight
 * on→off→on (SOS), a counter, repeated sensor reads, a retry-until-condition loop, or a user-asked
 * «повтори N раз» — because the CALL was the same while the world changed, i.e. it was progress,
 * not a loop.
 *
 * This guard tracks BOTH:
 *  - the CALL signature (which tool(s) with which arguments), and
 *  - a PROGRESS signature (the observable outcome of running the batch: each tool's
 *    success/failure plus a short digest of its result text).
 *
 * It reports a stuck loop when either:
 *  - the CALL signature is identical to the previous iteration AND the PROGRESS signature is also
 *    identical — the exact same action produced the exact same result, again and again; or
 *  - the last six iterations are two repetitions of the same three-step sequence, results included
 *    (A→B→C→A→B→C) — a loop that never matches the "identical to the previous" rule.
 *
 * Any change in the result (on↔off, a new counter value, fresh sensor data) is progress and resets
 * the counter; two-step alternation is therefore never treated as a cycle. The per-run iteration and
 * wall-clock caps of the orchestrator loop and [RunLifecyclePolicy] remain the ultimate bound.
 *
 * Pure and Android-free so the contract is unit-tested without a device.
 */
class LoopGuard(
    /**
     * Consecutive iterations whose call signature AND progress signature are both identical to the
     * previous one, before the run is treated as stuck. Kept equal to the orchestrator's
     * `MAX_IDENTICAL_ITERATIONS` so the two never drift; the orchestrator constructs this guard with
     * its own constant.
     */
    private val maxIdenticalIterations: Int = DEFAULT_MAX_IDENTICAL_ITERATIONS
) {

    private var lastCallSignature: String? = null
    private var lastProgressSignature: String? = null

    /** Consecutive identical (call + progress) iterations; reset to 1 on the first non-matching one. */
    private var repeatCount = 0

    /** The last [CYCLE_WINDOW] iterations, as (call, progress) pairs, oldest first. */
    private val history = ArrayDeque<Pair<String, String>>()

    /**
     * Count of consecutive iterations that were identical (call + progress) to the one before them.
     * `1` right after the first recorded iteration; grows only while every new iteration matches the
     * previous one on both signatures.
     */
    val consecutiveRepeats: Int get() = repeatCount

    /**
     * Period of the detected cycle (2 or 3) when the last stop was caused by a repeating SEQUENCE
     * rather than by one identical iteration repeated back to back; `0` otherwise. A→B→A→B and
     * A→B→C→A→B→C are loops too, and neither ever matches the "identical to the previous" rule.
     */
    var detectedCycle: Int = 0
        private set

    /**
     * Feeds one tool-loop iteration.
     *
     * @param callSignature identifies the batch the model asked for (tool names + arguments).
     * @param progressSignature describes the observable outcome of running that batch (per-tool
     *    success/failure + a short digest of each result).
     * @return `true` when THIS iteration is a stuck loop and the caller should stop honestly; `false`
     *    when it is progress and the loop should continue. The very first iteration always returns
     *    `false`.
     */
    fun record(callSignature: String, progressSignature: String): Boolean {
        if (callSignature == lastCallSignature && progressSignature == lastProgressSignature) {
            repeatCount++
        } else {
            lastCallSignature = callSignature
            lastProgressSignature = progressSignature
            repeatCount = 1
        }
        history.addLast(callSignature to progressSignature)
        while (history.size > CYCLE_WINDOW) history.removeFirst()

        if (repeatCount >= maxIdenticalIterations) {
            detectedCycle = 0
            return true
        }
        val period = detectCycle()
        if (period > 0) {
            detectedCycle = period
            return true
        }
        return false
    }

    /**
     * A three-step cycle (A→B→C→A→B→C) is a loop too: nothing else catches it, because no iteration
     * is ever identical to the one before it. Comparing whole (call, progress) pairs keeps genuine
     * repetition whose results change (on↔off, counters) out of this rule.
     *
     * Two-step alternation (A→B→A→B) is deliberately NOT treated as a cycle: blinking a flashlight
     * on→off→on, toggling a setting, or polling a two-state sensor is a legitimate pattern that
     * BUG-045 explicitly protects, and its per-step results are stable by nature.
     */
    private fun detectCycle(): Int {
        for (period in MIN_CYCLE_PERIOD..MAX_CYCLE_PERIOD) {
            val needed = period * 2
            if (history.size < needed) continue
            val tail = history.toList().takeLast(needed)
            if ((0 until period).all { tail[it] == tail[it + period] }) return period
        }
        return 0
    }

    /** Clears all state (a new turn starts). */
    fun reset() {
        lastCallSignature = null
        lastProgressSignature = null
        repeatCount = 0
        history.clear()
        detectedCycle = 0
    }

    companion object {
        /** Default consecutive identical call+progress iterations before a run is treated as stuck. */
        const val DEFAULT_MAX_IDENTICAL_ITERATIONS = 3

        /**
         * Shortest cycle period the guard recognises. Two-step alternation is legitimate repetition
         * (flashlight on↔off, BUG-045), so only three-step cycles count.
         */
        const val MIN_CYCLE_PERIOD = 3

        /** Longest cycle period the guard recognises. */
        const val MAX_CYCLE_PERIOD = 3

        /** How many recent iterations are kept for cycle detection (two full 3-step cycles). */
        const val CYCLE_WINDOW = MAX_CYCLE_PERIOD * 2
    }
}
