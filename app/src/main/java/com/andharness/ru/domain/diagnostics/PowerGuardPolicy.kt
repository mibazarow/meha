package com.andharness.ru.domain.diagnostics


/**
 * Deterministic, JVM-testable power-guard rules:
 *
 * - thermal status >= [THERMAL_STATUS_SEVERE] (3) → DEFER: the device is already throttling itself,
 *   extra background work would make it worse;
 * - battery <= [CRITICAL_BATTERY_PERCENT] % while NOT charging → DEFER: the remaining charge is
 *   better spent keeping the phone alive;
 * - an explicit user request (`essential = true`) is always ALLOWed;
 * - an unreadable snapshot (API quirks) is ALLOWed, because refusing to work on unknown data would
 *   be a lie — the unknowns are reported separately in the status text.
 *
 * Official references:
 * - Thermal status: https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()
 * - Battery saver: https://developer.android.com/reference/android/os/PowerManager#isPowerSaveMode()
 * - Battery-optimization exemption: https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
 * - Doze / App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
 */
object PowerGuardPolicy {

    /** `PowerManager.THERMAL_STATUS_*` values (API 29+). */
    const val THERMAL_STATUS_NONE = 0
    const val THERMAL_STATUS_LIGHT = 1
    const val THERMAL_STATUS_MODERATE = 2
    const val THERMAL_STATUS_SEVERE = 3
    const val THERMAL_STATUS_CRITICAL = 4
    const val THERMAL_STATUS_EMERGENCY = 5
    const val THERMAL_STATUS_SHUTDOWN = 6

    /** Below or equal to this level (and not charging) non-essential work is deferred. */
    const val CRITICAL_BATTERY_PERCENT = 5

    fun thermalLabel(status: Int): String = when (status) {
        THERMAL_STATUS_NONE -> "нормальный"
        THERMAL_STATUS_LIGHT -> "лёгкий нагрев"
        THERMAL_STATUS_MODERATE -> "умеренный нагрев"
        THERMAL_STATUS_SEVERE -> "сильный нагрев"
        THERMAL_STATUS_CRITICAL -> "критический нагрев"
        THERMAL_STATUS_EMERGENCY -> "аварийный нагрев"
        THERMAL_STATUS_SHUTDOWN -> "перегрев, выключение"
        -1 -> "недоступно (API < 29)"
        else -> "неизвестный статус ($status)"
    }

    /** True when the thermal status alone is enough to defer non-essential work. */
    fun isThermalCritical(status: Int): Boolean = status >= THERMAL_STATUS_SEVERE

    /** True when the battery alone is enough to defer non-essential work. */
    fun isBatteryCritical(snapshot: PowerSnapshot): Boolean =
        snapshot.levelPercent in 0..CRITICAL_BATTERY_PERCENT && !snapshot.charging

    /**
     * Decides what to do with non-essential background work. [essential] marks a run the user
     * explicitly requested (it always wins). The reason is composed from every triggered rule so the
     * log tells the whole truth.
     */
    fun evaluate(snapshot: PowerSnapshot, essential: Boolean = false): PowerVerdict {
        if (essential) return PowerVerdict(PowerAction.ALLOW, null, snapshot)
        if (!snapshot.known) return PowerVerdict(PowerAction.ALLOW, null, snapshot)

        val reasons = ArrayList<String>(2)
        if (isBatteryCritical(snapshot)) {
            reasons += "заряд ${snapshot.levelPercent}% и не заряжается"
        }
        if (isThermalCritical(snapshot.thermalStatus)) {
            reasons += "тепловой статус: ${thermalLabel(snapshot.thermalStatus)}"
        }
        return if (reasons.isEmpty()) {
            PowerVerdict(PowerAction.ALLOW, null, snapshot)
        } else {
            PowerVerdict(PowerAction.DEFER, reasons.joinToString("; "), snapshot)
        }
    }
}
