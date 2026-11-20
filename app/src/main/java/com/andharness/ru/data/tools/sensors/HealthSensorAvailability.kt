package com.andharness.ru.data.tools.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.andharness.ru.domain.automation.TriggerConfigs

/**
 * Read-only bridge between the real `SensorManager` and the pure [TriggerConfigs.HealthSensorKind]
 * set. Shared by [HealthSensorsToolsHandler] (the `health_sensors_list` tool) and
 * `HealthSensorMonitor` (the automation triggers), so both agree on what "the sensor exists" means.
 *
 * Sensor string types are read through [Sensor.getStringType] (API 20+, available at minSdk 26):
 * https://developer.android.com/reference/android/hardware/Sensor#getStringType()
 *
 * SpO₂ has no public `Sensor.TYPE_*` constant, so it is detected heuristically from the vendor
 * string type / sensor name — the caller must present it as a vendor-specific sensor, not a
 * guaranteed one.
 */
object HealthSensorAvailability {

    /** The health-relevant sensors actually present on this device. */
    fun available(context: Context): Set<TriggerConfigs.HealthSensorKind> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return emptySet()
        return available(sm)
    }

    fun available(sm: SensorManager): Set<TriggerConfigs.HealthSensorKind> {
        val out = mutableSetOf<TriggerConfigs.HealthSensorKind>()
        if (sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null) out += TriggerConfigs.HealthSensorKind.HEART_RATE
        if (sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) out += TriggerConfigs.HealthSensorKind.STEP_COUNTER
        if (sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null) {
            out += TriggerConfigs.HealthSensorKind.SIGNIFICANT_MOTION
        }
        if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) out += TriggerConfigs.HealthSensorKind.ACCELEROMETER
        if (spo2Sensor(sm) != null) out += TriggerConfigs.HealthSensorKind.SPO2
        return out
    }

    /** First sensor whose vendor string type / name looks like a pulse-oximeter (SpO₂), or null. */
    fun spo2Sensor(sm: SensorManager): Sensor? =
        sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { isSpo2(it) }

    /**
     * Vendor SpO₂ heuristic. `TYPE_ALL` includes vendor types with a `stringType` like
     * `com.samsung.sensor.…` or a name containing "SpO2"; there is no public constant, so this is a
     * best-effort name match and may miss an exotic vendor.
     */
    fun isSpo2(sensor: Sensor): Boolean {
        val type = runCatching { sensor.stringType }.getOrNull().orEmpty().lowercase()
        val name = sensor.name.orEmpty().lowercase()
        return type.contains("spo2") || type.contains("oxygen") ||
            name.contains("spo2") || name.contains("oxygen") || name.contains("oximeter")
    }
}
