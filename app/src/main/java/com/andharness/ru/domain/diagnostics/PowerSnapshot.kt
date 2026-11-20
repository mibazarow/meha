package com.andharness.ru.domain.diagnostics


/**
 * A point-in-time, honest snapshot of the device's power situation. Values that could not be read
 * are reported as unknown instead of being guessed: [levelPercent] is negative when unknown and
 * [thermalStatus] below zero means "not available" (API < 29).
 */
data class PowerSnapshot(
    val levelPercent: Int,
    val charging: Boolean,
    val batterySaver: Boolean,
    val thermalStatus: Int,
    val batteryOptimizationExempt: Boolean
) {
    /** True when at least one meaningful signal was actually read from the system. */
    val known: Boolean get() = levelPercent >= 0 || thermalStatus >= 0
}
