package com.andharness.ru.data.tools.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `health_sensors_list` (read-only): lists the health-relevant sensors this device actually has,
 * with an HONEST "недоступен" line for the ones it does not. It never fakes a sensor and never
 * returns an error for an absent one — absence is the answer.
 *
 * Sources (official Android docs):
 * - `SensorManager.getSensorList` / `getDefaultSensor`:
 *   https://developer.android.com/reference/android/hardware/SensorManager
 * - `Sensor`: `stringType`, `vendor`, `maximumRange`, `isWakeUpSensor`:
 *   https://developer.android.com/reference/android/hardware/Sensor
 * - `TYPE_HEART_RATE` requires `BODY_SENSORS`:
 *   https://developer.android.com/reference/android/hardware/Sensor#TYPE_HEART_RATE
 * - `TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR` require `ACTIVITY_RECOGNITION` (API 29+):
 *   https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
 */
class HealthSensorsToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "health_sensors_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val sm = context.getSystemService(SensorManager::class.java)
                        ?: return@withContext ToolResult(false, "SensorManager недоступен на устройстве.")
                    val sb = StringBuilder()
                    sb.append("Датчики здоровья (SensorManager):")

                    var available = 0
                    val missing = ArrayList<String>()

                    listOf(
                        Request("heart_rate (пульс)", Sensor.TYPE_HEART_RATE, Manifest.permission.BODY_SENSORS),
                        Request("step_counter (счётчик шагов)", Sensor.TYPE_STEP_COUNTER, Manifest.permission.ACTIVITY_RECOGNITION),
                        Request("step_detector (детектор шагов)", Sensor.TYPE_STEP_DETECTOR, Manifest.permission.ACTIVITY_RECOGNITION),
                        Request("significant_motion (значимое движение)", Sensor.TYPE_SIGNIFICANT_MOTION, null),
                        Request("accelerometer (нужен для «нет движения» и сон-эвристики)", Sensor.TYPE_ACCELEROMETER, null),
                    ).forEach { request ->
                        val sensor = sm.getDefaultSensor(request.type)
                        if (sensor == null) {
                            sb.append("\n- ${request.label}: НЕТ — датчик отсутствует на устройстве")
                            missing += request.label
                        } else {
                            available++
                            sb.append("\n- ${request.label}: ").append(describe(sensor, request.permission))
                        }
                    }

                    val spo2 = HealthSensorAvailability.spo2Sensor(sm)
                    if (spo2 == null) {
                        sb.append(
                            "\n- spo2: НЕТ — вендорный датчик SpO₂ не обнаружен " +
                                "(публичного TYPE_OXYGEN в Android нет)"
                        )
                        missing += "spo2"
                    } else {
                        available++
                        sb.append("\n- spo2: ").append(describe(spo2, null))
                    }

                    sb.append("\nИтог: доступно $available, отсутствует ${missing.size}")
                    if (missing.isNotEmpty()) sb.append(" (${missing.joinToString(", ")})")
                    ToolResult(true, sb.toString())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения датчиков: ${e.message}")
                }
            }
        },
    )

    private fun describe(sensor: Sensor, permission: String?): String = buildString {
        append("тип=${runCatching { sensor.stringType }.getOrNull() ?: sensor.type.toString()}")
        append(" | name=${sensor.name}")
        append(" | vendor=${sensor.vendor}")
        append(" | maxRange=${"%.3f".format(sensor.maximumRange)}")
        append(" | wakeUp=${sensor.isWakeUpSensor}")
        if (permission != null) {
            append(" | разрешение ${permission.substringAfterLast('.')}=")
            append(if (granted(permission)) "выдано" else "НЕ выдано")
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class Request(val label: String, val type: Int, val permission: String?)
}
