package com.andharness.ru.data.tools.system

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds one [ToolHandler] per system-control tool: [volume_get], [brightness_get], [ringer_set],
 * [rotation_set], [screen_timeout_set], [vibrate].
 *
 * References (official Android docs):
 * - `AudioManager.getStreamVolume` / `getStreamMaxVolume` / `setRingerMode` + RINGER_MODE_*:
 *   https://developer.android.com/reference/android/media/AudioManager
 * - `Settings.System.SCREEN_BRIGHTNESS` / `SCREEN_OFF_TIMEOUT` / `ACCELEROMETER_ROTATION` +
 *   `Settings.System.canWrite` (requires the WRITE_SETTINGS special permission):
 *   https://developer.android.com/reference/android/provider/Settings.System
 * - `Vibrator` / `VibratorManager` + `VibrationEffect.createOneShot` (needs VIBRATE):
 *   https://developer.android.com/reference/android/os/Vibrator
 *   https://developer.android.com/reference/android/os/VibrationEffect
 */
class SystemControlToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "volume_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val type = (arguments["type"] as? JsonPrimitive)?.content?.lowercase() ?: "music"
                    val am = context.getSystemService(AudioManager::class.java)
                    val stream = streamFor(type)
                    val current = am.getStreamVolume(stream)
                    val max = am.getStreamMaxVolume(stream)
                    ToolResult(true, "$type: $current (max $max)")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "brightness_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val raw = Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        0,
                    )
                    ToolResult(true, "Яркость: ${raw * 100 / 255}% ($raw/255)")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "ringer_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val mode = (arguments["mode"] as? JsonPrimitive)?.content?.lowercase() ?: ""
                    val target = when (mode) {
                        "normal", "ring" -> AudioManager.RINGER_MODE_NORMAL
                        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                        "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
                        else -> return@withContext ToolResult(false, "mode: normal | vibrate | silent")
                    }
                    val am = context.getSystemService(AudioManager::class.java)
                    am.ringerMode = target
                    val actual = am.ringerMode
                    // setRingerMode can be silently blocked (e.g. silent mode sometimes needs
                    // notification-policy access). Report the REAL resulting mode, not the request.
                    if (actual == target) {
                        ToolResult(true, "Режим звука: $mode")
                    } else {
                        ToolResult(false, "Система не применила режим «$mode» (текущий=${actual}). Возможно, нужен доступ к политике уведомлений.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "rotation_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val on = (arguments["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: return@withContext ToolResult(false, "Укажите on (true = авто-поворот).")
                    if (!Settings.System.canWrite(context)) {
                        return@withContext ToolResult(false, "Нет разрешения WRITE_SETTINGS для смены поворота экрана.")
                    }
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION,
                        if (on) 1 else 0,
                    )
                    ToolResult(true, if (on) "Авто-поворот включён." else "Авто-поворот выключен (ориентация зафиксирована).")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "screen_timeout_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val seconds = (arguments["seconds"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: return@withContext ToolResult(false, "Укажите seconds (целое число секунд).")
                    if (seconds <= 0) {
                        return@withContext ToolResult(false, "seconds должно быть > 0.")
                    }
                    if (!Settings.System.canWrite(context)) {
                        return@withContext ToolResult(false, "Нет разрешения WRITE_SETTINGS для смены таймаута экрана.")
                    }
                    // SCREEN_OFF_TIMEOUT is an Int (ms); convert in Long and clamp to avoid the
                    // 32-bit overflow that produced negative values. See ScreenTimeout.kt.
                    val millis = screenTimeoutMillisFromSeconds(seconds)
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        millis,
                    )
                    if (screenTimeoutClamped(seconds)) {
                        ToolResult(
                            true,
                            "Таймаут экрана: $seconds с превышает максимум Android; " +
                                "установлено максимально возможное значение $millis мс (~24.8 сут).",
                        )
                    } else {
                        ToolResult(true, "Таймаут экрана: $seconds с.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "vibrate"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val ms = (arguments["duration_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 500L
                    val duration = ms.coerceIn(1L, 10_000L)
                    vibrator()?.let { v ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            v.vibrate(duration)
                        }
                        ToolResult(true, "Вибрация: ${duration}мс")
                    } ?: ToolResult(false, "Вибратор недоступен на устройстве.")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )

    private fun streamFor(type: String): Int = when (type) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        else -> AudioManager.STREAM_MUSIC
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
