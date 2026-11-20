package com.andharness.ru.data.tools.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume
import com.andharness.ru.data.tools.str

/**
 * Builds one [ToolHandler] per sensor tool: [sensor_list], [sensor_read], [sensor_subscribe],
 * [sensor_unsubscribe] and [sensor_trigger_subscribe].
 *
 * All sampling is BOUNDED: a subscription registers a listener, collects at most [MAX_SAMPLES]
 * samples over a capped window, and always unregisters in a `finally` block. Nothing keeps a sensor
 * registered after the tool returns (except while an explicit window is still running, which
 * `sensor_unsubscribe` can end early through [SensorSubscriptionState]).
 *
 * HIGH SAMPLING RATES: since Android 12 (API 31) reading a sensor faster than 200 Hz requires the
 * `HIGH_SAMPLING_RATE_SENSORS` manifest permission (declared by this app, so it is granted at
 * install time). The `rate=fast` option maps to `SENSOR_DELAY_FASTEST`, whose actual period is
 * decided by the hardware and may be capped by the platform without that permission.
 *
 * References (official Android docs):
 * - `SensorManager` / `registerListener` / `SensorEventListener`:
 *   https://developer.android.com/reference/android/hardware/SensorManager
 * - `requestTriggerSensor` / `cancelTriggerSensor` / `TriggerEventListener`:
 *   https://developer.android.com/reference/android/hardware/SensorManager#requestTriggerSensor(android.hardware.TriggerEventListener,%20android.hardware.Sensor)
 * - `Sensor.TYPE_*` constants:
 *   https://developer.android.com/reference/android/hardware/Sensor
 * - Sensors overview / sampling rates:
 *   https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
 */
class SensorToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "sensor_list"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val sm = context.getSystemService(SensorManager::class.java)
                    val sensors = sm.getSensorList(Sensor.TYPE_ALL)
                    if (sensors.isNullOrEmpty()) {
                        ToolResult(true, "Датчики недоступны.")
                    } else {
                        ToolResult(true, sensors.joinToString("\n") { "${it.name} | ${it.stringType}" })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "sensor_read"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val typeName = arguments.str("type")?.lowercase()
                        ?: return@withContext ToolResult(false, "Укажите type (например accelerometer).")
                    val timeoutMs = arguments.str("timeout_ms")?.toLongOrNull()?.coerceIn(500, 10_000) ?: 3_000L
                    val sensorType = sensorTypeOf(typeName) ?: return@withContext unknownSensor(typeName)
                    permissionIssue(sensorType)?.let { return@withContext it }
                    val sm = context.getSystemService(SensorManager::class.java)
                    val sensor = sm.getDefaultSensor(sensorType)
                        ?: return@withContext ToolResult(false, "Датчик «$typeName» отсутствует на устройстве.")
                    val values = readOnce(sm, sensor, timeoutMs)
                    if (values == null) {
                        ToolResult(false, "Не удалось получить данные с датчика «$typeName» за ${timeoutMs}мс.")
                    } else {
                        ToolResult(true, "$typeName: " + values.joinToString(", ") { "%.3f".format(it) })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "sensor_subscribe"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val typeName = arguments.str("type")?.lowercase()
                        ?: return@withContext ToolResult(false, "Укажите type (например accelerometer).")
                    val sensorType = sensorTypeOf(typeName) ?: return@withContext unknownSensor(typeName)
                    permissionIssue(sensorType)?.let { return@withContext it }
                    val durationMs = arguments.str("duration_ms")?.toLongOrNull()
                        ?.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS) ?: DEFAULT_WINDOW_MS
                    val rateName = arguments.str("rate")?.lowercase() ?: "normal"
                    val delayUs = when (rateName) {
                        "normal", "обычный" -> SensorManager.SENSOR_DELAY_NORMAL
                        "fast", "быстрый" -> SensorManager.SENSOR_DELAY_FASTEST
                        "game", "игра" -> SensorManager.SENSOR_DELAY_GAME
                        else -> return@withContext ToolResult(
                            false,
                            "Неизвестный rate «$rateName». Доступно: normal | fast | game."
                        )
                    }

                    val sm = context.getSystemService(SensorManager::class.java)
                    val sensor = sm.getDefaultSensor(sensorType)
                        ?: return@withContext ToolResult(false, "Датчик «$typeName» отсутствует на устройстве.")

                    // Only one streaming subscription at a time — replace the previous one.
                    SensorSubscriptionState.stop()

                    val samples = ArrayList<FloatArray>()
                    val handler = Handler(Looper.getMainLooper())
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            synchronized(samples) {
                                if (samples.size < MAX_SAMPLES) samples.add(event.values.copyOf())
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }

                    val registered = try {
                        sm.registerListener(listener, sensor, delayUs, handler)
                    } catch (e: SecurityException) {
                        return@withContext ToolResult(
                            false,
                            "Система запретила регистрацию на такой частоте (нужно разрешение HIGH_SAMPLING_RATE_SENSORS " +
                                "или ниже rate). ${e.message.orEmpty()}"
                        )
                    }
                    if (!registered) {
                        return@withContext ToolResult(
                            false,
                            "Не удалось подписаться на датчик «$typeName» (система отклонила регистрацию)."
                        )
                    }

                    SensorSubscriptionState.setActive(sm, listener, typeName)
                    try {
                        delay(durationMs)
                    } finally {
                        sm.unregisterListener(listener)
                        SensorSubscriptionState.clearIf(listener)
                    }

                    val snapshot = synchronized(samples) { ArrayList(samples) }
                    if (snapshot.isEmpty()) {
                        return@withContext ToolResult(
                            false,
                            "За ${durationMs}мс датчик «$typeName» не прислал ни одного отсчёта (rate=$rateName)."
                        )
                    }

                    val sb = StringBuilder()
                    sb.append("Датчик «$typeName»: ${snapshot.size} отсчётов за ${durationMs}мс, rate=$rateName")
                    if (snapshot.size >= MAX_SAMPLES) {
                        sb.append(" (достигнут лимит $MAX_SAMPLES отсчётов)")
                    }
                    val axes = snapshot.maxOf { it.size }
                    for (axis in 0 until axes) {
                        val column = snapshot.map { it[axis] }
                        sb.append(
                            "\nось ${axis + 1}: min=%.3f max=%.3f avg=%.3f".format(
                                column.min(), column.max(), column.average()
                            )
                        )
                    }
                    sb.append("\nпоследние: " + snapshot.last().joinToString(", ") { "%.3f".format(it) })
                    if (sensorType == Sensor.TYPE_STEP_COUNTER) {
                        sb.append("\n(значение счётчика шагов накопительное — с момента загрузки устройства)")
                    }
                    if (rateName == "fast") {
                        sb.append("\nrate=fast — максимальная частота, реальный период определяет железо.")
                    }
                    ToolResult(true, sb.toString())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "sensor_unsubscribe"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val type = SensorSubscriptionState.stop()
                    if (type == null) {
                        ToolResult(true, "Активной подписки на датчик нет — останавливать нечего.")
                    } else {
                        ToolResult(true, "Подписка на датчик «$type» остановлена, слушатель снят.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "sensor_trigger_subscribe"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val typeName = arguments.str("type")?.lowercase()
                        ?: return@withContext ToolResult(
                            false,
                            "Укажите type — trigger-датчик (например significant_motion)."
                        )
                    val sensorType = sensorTypeOf(typeName) ?: return@withContext unknownSensor(typeName)
                    permissionIssue(sensorType)?.let { return@withContext it }
                    val durationMs = arguments.str("duration_ms")?.toLongOrNull()
                        ?.coerceIn(MIN_TRIGGER_WINDOW_MS, MAX_TRIGGER_WINDOW_MS) ?: DEFAULT_TRIGGER_WINDOW_MS

                    val sm = context.getSystemService(SensorManager::class.java)
                    val sensor = sm.getDefaultSensor(sensorType)
                        ?: return@withContext ToolResult(false, "Датчик «$typeName» отсутствует на устройстве.")
                    if (!sensor.isWakeUpSensor) {
                        return@withContext ToolResult(
                            false,
                            "Датчик «$typeName» не является trigger/wake-up датчиком — requestTriggerSensor для него " +
                                "не имеет смысла. Примеры trigger-датчиков: significant_motion, step_detector."
                        )
                    }

                    val events = ArrayList<TriggerEvent>()
                    val listener = object : TriggerEventListener() {
                        override fun onTrigger(event: TriggerEvent) {
                            synchronized(events) { events.add(event) }
                        }
                    }

                    val requested = try {
                        sm.requestTriggerSensor(listener, sensor)
                    } catch (e: Exception) {
                        false
                    }
                    if (!requested) {
                        return@withContext ToolResult(
                            false,
                            "Система не приняла подписку на trigger-датчик «$typeName»."
                        )
                    }

                    try {
                        delay(durationMs)
                    } finally {
                        runCatching { sm.cancelTriggerSensor(listener, sensor) }
                    }

                    val snapshot = synchronized(events) { ArrayList(events) }
                    if (snapshot.isEmpty()) {
                        ToolResult(
                            true,
                            "За ${durationMs}мс trigger-датчик «$typeName» не сработал (события не произошло)."
                        )
                    } else {
                        ToolResult(true, buildString {
                            append("Trigger-датчик «$typeName»: срабатываний — ${snapshot.size}")
                            snapshot.forEachIndexed { index, event ->
                                append("\n${index + 1}: t=${event.timestamp} значения: ")
                                append(event.values.joinToString(", ") { "%.3f".format(it) })
                            }
                        })
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private suspend fun readOnce(sm: SensorManager, sensor: Sensor, timeoutMs: Long): FloatArray? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.copyOf())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
        }

    /**
     * Step counter / step detector need `ACTIVITY_RECOGNITION` on Android 10+ (API 29+); the
     * activity-recognition sensors are privacy-sensitive. https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
     */
    private fun permissionIssue(sensorType: Int): ToolResult? {
        if (sensorType == Sensor.TYPE_STEP_COUNTER || sensorType == Sensor.TYPE_STEP_DETECTOR) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return ToolResult(false, "PERMISSION_REQUIRED:android.permission.ACTIVITY_RECOGNITION")
            }
        }
        return null
    }

    private fun unknownSensor(name: String): ToolResult = ToolResult(
        false,
        "Неизвестный датчик «$name». Примеры: accelerometer, gyroscope, magnetometer, light, proximity, " +
            "pressure, gravity, linear_acceleration, rotation_vector, step_counter, step_detector, " +
            "significant_motion, humidity, temperature."
    )

    private fun sensorTypeOf(name: String): Int? = when (name) {
        "accelerometer", "accel" -> Sensor.TYPE_ACCELEROMETER
        "gyroscope", "gyro" -> Sensor.TYPE_GYROSCOPE
        "magnetometer", "magnetic" -> Sensor.TYPE_MAGNETIC_FIELD
        "light" -> Sensor.TYPE_LIGHT
        "proximity" -> Sensor.TYPE_PROXIMITY
        "pressure" -> Sensor.TYPE_PRESSURE
        "gravity" -> Sensor.TYPE_GRAVITY
        "linear_acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
        "rotation_vector" -> Sensor.TYPE_ROTATION_VECTOR
        "step_counter", "steps" -> Sensor.TYPE_STEP_COUNTER
        "step_detector" -> Sensor.TYPE_STEP_DETECTOR
        "significant_motion", "motion" -> Sensor.TYPE_SIGNIFICANT_MOTION
        "humidity", "relative_humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
        "temperature", "ambient_temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
        else -> null
    }


    private companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
        const val MIN_WINDOW_MS = 200L
        const val MAX_WINDOW_MS = 10_000L

        const val DEFAULT_TRIGGER_WINDOW_MS = 30_000L
        const val MIN_TRIGGER_WINDOW_MS = 1_000L
        const val MAX_TRIGGER_WINDOW_MS = 300_000L

        const val MAX_SAMPLES = 2_000
    }
}
