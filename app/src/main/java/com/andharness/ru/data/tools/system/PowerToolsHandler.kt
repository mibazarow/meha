package com.andharness.ru.data.tools.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds one [ToolHandler] per power / battery / thermal state tool: [thermal_status],
 * [battery_prediction], [power_saver_get], [device_idle_get], [battery_optimization_status],
 * [battery_optimization_request], [screen_state_get].
 *
 * Every tool here is read-only except [battery_optimization_request], which only opens the
 * documented system dialog — it never changes a system setting silently. All of these return an
 * honest failure/limitation message when the platform does not expose the value (older API level,
 * charging device, missing system screen) instead of inventing a value.
 *
 * References (official Android docs / AOSP sources):
 * - `PowerManager.getCurrentThermalStatus()` and the `THERMAL_STATUS_*` constants (added API 29):
 *   https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()
 * - `PowerManager.getBatteryDischargePrediction()` (added API 28; returns `null` when the device is
 *   powered, charging, or no estimate could be produced):
 *   https://developer.android.com/reference/android/os/PowerManager
 * - `PowerManager.isPowerSaveMode()` (added API 21) and `isInteractive()` (added API 20) — no
 *   permission required for either:
 *   https://developer.android.com/reference/android/os/PowerManager#isPowerSaveMode()
 *   https://developer.android.com/reference/android/os/PowerManager#isInteractive()
 * - `PowerManager.isDeviceIdleMode()` (added API 23) / `isDeviceLightIdleMode()` (public since
 *   API 33 — the pre-33 `isLightDeviceIdleMode()` is hidden):
 *   https://developer.android.com/reference/android/os/PowerManager#isDeviceIdleMode()
 *   https://developer.android.com/reference/android/os/PowerManager#isDeviceLightIdleMode()
 * - `PowerManager.isIgnoringBatteryOptimizations(String)` (added API 23):
 *   https://developer.android.com/reference/android/os/PowerManager#isIgnoringBatteryOptimizations(java.lang.String)
 * - `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — data URI must use the `package:`
 *   scheme, `FLAG_ACTIVITY_NEW_TASK` is required because the Intent is started from a non-Activity
 *   Context, and the app must hold the manifest permission
 *   `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the screen to be shown:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 */
class PowerToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "thermal_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pm = powerManager()
                        ?: return@withContext ToolResult(false, "Служба PowerManager недоступна на этом устройстве.")
                    // getCurrentThermalStatus() was added in API 29 (Android 10); calling it on an
                    // older device would crash, so it is guarded with Build.VERSION.SDK_INT:
                    // https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        return@withContext ToolResult(
                            false,
                            "Термальный статус недоступен: требуется Android 10 (API 29) или новее, на этом устройстве — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).",
                        )
                    }
                    val status = pm.currentThermalStatus
                    val text = when (status) {
                        PowerManager.THERMAL_STATUS_NONE -> "none — троттлинга нет"
                        PowerManager.THERMAL_STATUS_LIGHT -> "light — лёгкий троттлинг"
                        PowerManager.THERMAL_STATUS_MODERATE -> "moderate — умеренный троттлинг"
                        PowerManager.THERMAL_STATUS_SEVERE -> "severe — серьёзный троттлинг"
                        PowerManager.THERMAL_STATUS_CRITICAL -> "critical — критический троттлинг"
                        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency — аварийный троттлинг"
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown — устройство будет выключено из-за перегрева"
                        else -> "неизвестный код ($status)"
                    }
                    ToolResult(true, "Термальный статус: $text")
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось получить термальный статус: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "battery_prediction"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pm = powerManager()
                        ?: return@withContext ToolResult(false, "Служба PowerManager недоступна на этом устройстве.")
                    // getBatteryDischargePrediction() was added in API 28 (Android 9).
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        return@withContext ToolResult(
                            false,
                            "Прогноз времени работы от батареи недоступен: требуется Android 9 (API 28) или новее, на этом устройстве — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).",
                        )
                    }
                    val duration = pm.batteryDischargePrediction
                    if (duration == null) {
                        // Documented: null when the device is powered, charging, or no estimate
                        // could be produced. We must not invent a value.
                        ToolResult(
                            false,
                            "Прогноз времени работы недоступен: устройство подключено к питанию/заряжается либо система не смогла рассчитать прогноз.",
                        )
                    } else {
                        val totalMinutes = duration.toMinutes()
                        val hours = totalMinutes / 60
                        val minutes = totalMinutes % 60
                        val text = if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
                        ToolResult(true, "Прогноз оставшегося времени работы от батареи: $text.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось получить прогноз работы от батареи: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "power_saver_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pm = powerManager()
                        ?: return@withContext ToolResult(false, "Служба PowerManager недоступна на этом устройстве.")
                    val enabled = pm.isPowerSaveMode
                    ToolResult(
                        true,
                        if (enabled) "Режим энергосбережения включён." else "Режим энергосбережения выключен.",
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось определить режим энергосбережения: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "device_idle_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pm = powerManager()
                        ?: return@withContext ToolResult(false, "Служба PowerManager недоступна на этом устройстве.")
                    val sb = StringBuilder()
                    sb.appendLine(if (pm.isDeviceIdleMode) "Doze (idle): да" else "Doze (idle): нет")
                    // isDeviceLightIdleMode() is public only since API 33 (Android 13); before that
                    // the light-idle flag was hidden, so we say so honestly instead of guessing.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        sb.appendLine(if (pm.isDeviceLightIdleMode) "Light idle: да" else "Light idle: нет")
                    } else {
                        sb.appendLine("Light idle: недоступно (нужен Android 13 / API 33+)")
                    }
                    ToolResult(true, sb.toString().trim())
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось определить режим Doze/Idle: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "battery_optimization_status"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pm = powerManager()
                        ?: return@withContext ToolResult(false, "Служба PowerManager недоступна на этом устройстве.")
                    val pkg = context.packageName
                    val ignoring = pm.isIgnoringBatteryOptimizations(pkg)
                    ToolResult(
                        true,
                        if (ignoring) {
                            "Приложение исключено из оптимизации батареи (в белом списке энергосбережения)."
                        } else {
                            "Приложение НЕ исключено из оптимизации батареи: система может ограничивать его фоновую работу."
                        },
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось определить статус оптимизации батареи: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "battery_optimization_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val handler = intent.resolveActivity(context.packageManager)
                        ?: return@withContext ToolResult(
                            false,
                            "Система не предоставила экран запроса на исключение из оптимизации батареи (нет подходящей activity). Пользователь может сделать это вручную в настройках батареи.",
                        )
                    context.startActivity(intent)
                    ToolResult(
                        true,
                        "Запущен системный экран запроса исключения из оптимизации батареи (${handler.packageName}). Подтверждение делает пользователь вручную — после этого проверь battery_optimization_status.",
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось открыть запрос на исключение из оптимизации батареи: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "screen_state_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val pm = powerManager()
                        ?: return@withContext ToolResult(false, "Служба PowerManager недоступна на этом устройстве.")
                    val interactive = pm.isInteractive
                    ToolResult(
                        true,
                        if (interactive) {
                            "Экран включён, устройство интерактивно (isInteractive = true)."
                        } else {
                            "Экран выключен, устройство неинтерактивно (isInteractive = false)."
                        },
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Не удалось определить состояние экрана: ${e.message}")
                }
            }
        },
    )

    private fun powerManager(): PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
}
