package com.andharness.ru.domain.objective

import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot

/**
 * Pure policy for how many background objectives may run at once. Kept Android-free so the
 * "power guard downgrades concurrency to 1" rule is unit-tested without a device.
 *
 * Rules:
 * - the configured limit is always clamped to [MIN]..[MAX] (1..3);
 * - when the device is degraded — battery saver on, battery at/below [LOW_BATTERY_PERCENT] while not
 *   charging, or thermal status >= MODERATE — the effective limit is reduced to 1 (and the reason is
 *   reported honestly);
 * - when the whole snapshot is unknown, the configured limit is used (refusing to work on unknown
 *   data would be a lie).
 *
 * The stronger *defer* decision (severe thermal / critical battery) still comes from
 * [PowerGuardPolicy.evaluate] and is applied by the coordinator BEFORE this policy.
 */
object ObjectiveConcurrencyPolicy {

    const val MIN = 1
    const val MAX = 3
    const val DEFAULT = 2

    /** At/below this level (while not charging) concurrency is reduced to 1. */
    const val LOW_BATTERY_PERCENT = 20

    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)

    /** True when the device is degraded enough to justify running only one objective at a time. */
    fun isDegraded(snapshot: PowerSnapshot): Boolean {
        if (!snapshot.known) return false
        if (snapshot.batterySaver) return true
        if (snapshot.levelPercent in 0..LOW_BATTERY_PERCENT && !snapshot.charging) return true
        return snapshot.thermalStatus >= PowerGuardPolicy.THERMAL_STATUS_MODERATE
    }

    /** Effective concurrency limit given the device power [snapshot]. */
    fun effectiveLimit(desired: Int, snapshot: PowerSnapshot): Int =
        if (isDegraded(snapshot)) MIN else clamp(desired)

    /** Honest one-line reason for a downgrade, or null when no downgrade happened. */
    fun degradedReason(snapshot: PowerSnapshot): String? {
        if (!isDegraded(snapshot)) return null
        val reasons = ArrayList<String>(2)
        if (snapshot.batterySaver) reasons += "включён режим энергосбережения"
        if (snapshot.levelPercent in 0..LOW_BATTERY_PERCENT && !snapshot.charging) {
            reasons += "низкий заряд ${snapshot.levelPercent}% и не заряжается"
        }
        if (snapshot.thermalStatus >= PowerGuardPolicy.THERMAL_STATUS_MODERATE) {
            reasons += "нагрев: ${PowerGuardPolicy.thermalLabel(snapshot.thermalStatus)}"
        }
        return reasons.joinToString("; ")
    }
}
