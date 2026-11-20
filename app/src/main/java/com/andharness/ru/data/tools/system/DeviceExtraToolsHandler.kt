package com.andharness.ru.data.tools.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import com.andharness.ru.data.tools.str

/**
 * Builds one [ToolHandler] per extra device tool: [battery_detail], [alarm_set], [timer_set],
 * [text_to_speech], [font_scale_set].
 *
 * References (official Android docs):
 * - Battery changed sticky broadcast / `BatteryManager.EXTRA_*`:
 *   https://developer.android.com/reference/android/os/BatteryManager
 * - `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` (+ EXTRA_HOUR/MINUTES/LENGTH/SKIP_UI):
 *   https://developer.android.com/reference/android/provider/AlarmClock
 * - `TextToSpeech` (init listener, speak, QUEUE_FLUSH):
 *   https://developer.android.com/reference/android/speech/tts/TextToSpeech
 * - `Settings.System.FONT_SCALE` + `Settings.System.canWrite` (WRITE_SETTINGS):
 *   https://developer.android.com/reference/android/provider/Settings.System
 */
class DeviceExtraToolsHandler(
    private val context: Context,
) {

    /** Kept referenced while speaking so the engine is not garbage-collected prematurely. */
    private var tts: TextToSpeech? = null

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "battery_detail"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        ?: return@withContext ToolResult(false, "Не удалось получить состояние батареи.")
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                    val voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                    val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val sb = StringBuilder()
                    if (percent >= 0) sb.appendLine("Заряд: $percent%")
                    if (tempC > 0) sb.appendLine("Температура: $tempC °C")
                    if (voltageMv > 0) sb.appendLine("Напряжение: $voltageMv мВ")
                    sb.appendLine("Состояние: ${statusText(status)}")
                    sb.appendLine("Здоровье: ${healthText(health)}")
                    sb.appendLine("Питание: ${pluggedText(plugged)}")
                    ToolResult(true, sb.toString().trim())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "alarm_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val hour = arguments.str("hour")?.toIntOrNull()
                        ?: return@withContext ToolResult(false, "Укажите hour (0-23).")
                    val minute = arguments.str("minute")?.toIntOrNull() ?: 0
                    if (hour !in 0..23 || minute !in 0..59) {
                        return@withContext ToolResult(false, "Некорректное время.")
                    }
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        arguments.str("message")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Будильник установлен на %02d:%02d.".format(hour, minute))
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "timer_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val seconds = arguments.str("seconds")?.toIntOrNull()
                        ?: return@withContext ToolResult(false, "Укажите seconds.")
                    if (seconds <= 0) return@withContext ToolResult(false, "seconds должно быть > 0.")
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        arguments.str("message")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolResult(true, "Таймер установлен на $seconds с.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "text_to_speech"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val text = arguments.str("text")
                    ?: return@withContext ToolResult(false, "Укажите text.")
                try {
                    tts?.shutdown()
                    val engine = TextToSpeech(context.applicationContext) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.apply {
                                setLanguage(Locale.getDefault())
                                speak(text, TextToSpeech.QUEUE_FLUSH, null, "andharness")
                            }
                        }
                    }
                    tts = engine
                    ToolResult(true, "Озвучиваю: $text")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "font_scale_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val scale = arguments.str("scale")?.toFloatOrNull()
                        ?: return@withContext ToolResult(false, "Укажите scale (например 1.0).")
                    val clamped = scale.coerceIn(0.5f, 2.0f)
                    if (!Settings.System.canWrite(context)) {
                        return@withContext ToolResult(false, "Нет разрешения WRITE_SETTINGS для изменения размера шрифта.")
                    }
                    Settings.System.putFloat(context.contentResolver, Settings.System.FONT_SCALE, clamped)
                    ToolResult(true, "Размер шрифта: $clamped")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun statusText(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "заряжается"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "разряжается"
        BatteryManager.BATTERY_STATUS_FULL -> "полный"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "не заряжается"
        else -> "неизвестно"
    }

    private fun healthText(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "хорошее"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "перегрев"
        BatteryManager.BATTERY_HEALTH_DEAD -> "изношена"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "перенапряжение"
        BatteryManager.BATTERY_HEALTH_COLD -> "переохлаждение"
        else -> "неизвестно"
    }

    private fun pluggedText(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "сеть"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "беспроводная"
        else -> "от батареи"
    }

}
