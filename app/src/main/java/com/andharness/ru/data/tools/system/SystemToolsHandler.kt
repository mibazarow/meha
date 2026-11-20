package com.andharness.ru.data.tools.system

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.LocaleList
import android.os.storage.StorageManager
import android.provider.Settings
import android.bluetooth.BluetoothAdapter
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds one [ToolHandler] per system tool: [brightness_set], [volume_set], [wifi_set],
 * [bluetooth_set], [locale_app_set].
 *
 * References:
 * - `Settings.System.SCREEN_BRIGHTNESS` + `Settings.System.canWrite(context)` +
 *   `Settings.ACTION_MANAGE_WRITE_SETTINGS`:
 *   https://developer.android.com/reference/android/provider/Settings.System
 *   (canWrite added API 23; WRITE_SETTINGS grant is a manual system-settings screen).
 * - `AudioManager.setStreamVolume(int, int, int)` + `STREAM_MUSIC`/`STREAM_RING`/
 *   `STREAM_NOTIFICATION`/`STREAM_ALARM`:
 *   https://developer.android.com/reference/android/media/AudioManager
 * - `WifiManager.setWifiEnabled(boolean)` (DEPRECATED API 29; returns false for apps
 *   targeting Q+; needs ACCESS_WIFI_STATE / CHANGE_WIFI_STATE):
 *   https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean)
 * - `BluetoothAdapter.enable()` / `disable()` (DEPRECATED API 33; API 31+ requires the
 *   runtime BLUETOOTH_CONNECT permission and throws SecurityException otherwise;
 *   API <=30 needs BLUETOOTH / BLUETOOTH_ADMIN):
 *   https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#enable()
 * - `CameraManager.setTorchMode(String, boolean)` + `CameraManager.getCameraIdList()` +
 *   `CameraCharacteristics.FLASH_INFO_AVAILABLE` (torch requires CAMERA permission;
 *   setTorchMode throws SecurityException without it):
 *   https://developer.android.com/reference/android/hardware/camera2/CameraManager
 * - `Context.STORAGE_SERVICE` + `StorageManager.getStorageVolumes()` (API 24; listing the
 *   volumes needs NO permission) and `StorageVolume.getDescription(Context)` / `isPrimary` /
 *   `isRemovable` / `getUuid` / `getState` (API 24), plus `StorageVolume.getDirectory()`
 *   (API 30 only — guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`):
 *   https://developer.android.com/reference/android/os/storage/StorageManager
 *   https://developer.android.com/reference/android/os/storage/StorageVolume
 *   https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT
 * - `LocaleManager.setApplicationLocales(LocaleList)` / `getApplicationLocales()` (API 33+,
 *   per-app language). An empty `LocaleList.getEmptyLocaleList()` resets the app to the system
 *   locale. The app must declare its supported locales via `android:localeConfig`:
 *   https://developer.android.com/reference/android/app/LocaleManager
 *   https://developer.android.com/guide/topics/resources/app-languages
 */
class SystemToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "brightness_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val level = ((arguments["level"] as? JsonPrimitive)?.content?.toIntOrNull()) ?: 0
                    val percent = level.coerceIn(0, 100)
                    val value = percent * 255 / 100
                    if (!Settings.System.canWrite(context)) {
                        ToolResult(false, "Нет разрешения WRITE_SETTINGS для смены яркости.")
                    } else {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            value,
                        )
                        ToolResult(true, "Яркость установлена: $percent%")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "volume_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val type = ((arguments["type"] as? JsonPrimitive)?.content ?: "music").lowercase()
                    val level = ((arguments["level"] as? JsonPrimitive)?.content?.toIntOrNull()) ?: 0
                    val audioManager = context.getSystemService(AudioManager::class.java)
                    val streamType = when (type) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val max = audioManager.getStreamMaxVolume(streamType)
                    val clamped = level.coerceIn(0, max)
                    audioManager.setStreamVolume(streamType, clamped, 0)
                    ToolResult(true, "Громкость установлена: $clamped (max $max)")
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "wifi_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val on = (arguments["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: return@withContext ToolResult(false, "Укажите on (true/false).")
                val kind = WifiBluetoothToggle.Kind.WIFI
                if (!WifiBluetoothToggle.toggleAllowed(kind, Build.VERSION.SDK_INT)) {
                    // Android 10+ forbids normal apps from toggling Wi-Fi programmatically.
                    // Open the panel and report honestly; never claim success.
                    val opened = openSettingsPanel(Settings.ACTION_WIFI_SETTINGS)
                    val wm = context.getSystemService(WifiManager::class.java)
                    val actual = wm?.isWifiEnabled ?: false
                    ToolResult(
                        false,
                        WifiBluetoothToggle.disallowedMessage(kind, on, opened) +
                            " Текущее состояние Wi-Fi: ${if (actual) "включён" else "выключен"}.",
                    )
                } else {
                    val wm = context.getSystemService(WifiManager::class.java)
                        ?: return@withContext ToolResult(false, "WifiManager недоступен на устройстве.")
                    try {
                        wm.setWifiEnabled(on)
                    } catch (e: SecurityException) {
                        return@withContext ToolResult(
                            false,
                            "Нет разрешения CHANGE_WIFI_STATE для переключения Wi-Fi.",
                        )
                    }
                    // Read back the REAL state — success only if the system actually changed it.
                    if (WifiBluetoothToggle.succeeded(on, wm.isWifiEnabled)) {
                        ToolResult(true, if (on) "Wi-Fi включён." else "Wi-Fi выключен.")
                    } else {
                        ToolResult(
                            false,
                            "Не удалось ${if (on) "включить" else "выключить"} Wi-Fi: система не сменила состояние.",
                        )
                    }
                }
            }
        },
        object : ToolHandler {
            override val name = "bluetooth_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val on = (arguments["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                    ?: return@withContext ToolResult(false, "Укажите on (true/false).")
                val kind = WifiBluetoothToggle.Kind.BLUETOOTH
                if (!WifiBluetoothToggle.toggleAllowed(kind, Build.VERSION.SDK_INT)) {
                    // Android 12+ forbids normal apps from toggling Bluetooth programmatically.
                    // Open the panel and report honestly; never claim success.
                    val opened = openSettingsPanel(Settings.ACTION_BLUETOOTH_SETTINGS)
                    val adapter = try {
                        BluetoothAdapter.getDefaultAdapter()
                    } catch (e: Exception) {
                        null
                    }
                    val actual = adapter?.isEnabled ?: false
                    ToolResult(
                        false,
                        WifiBluetoothToggle.disallowedMessage(kind, on, opened) +
                            " Текущее состояние Bluetooth: ${if (actual) "включён" else "выключен"}.",
                    )
                } else {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                        ?: return@withContext ToolResult(
                            false,
                            "Bluetooth недоступен на этом устройстве.",
                        )
                    try {
                        if (on) adapter.enable() else adapter.disable()
                    } catch (e: SecurityException) {
                        return@withContext ToolResult(
                            false,
                            "Нет разрешения BLUETOOTH_CONNECT для переключения Bluetooth.",
                        )
                    }
                    // Read back the REAL state — success only if the system actually changed it.
                    if (WifiBluetoothToggle.succeeded(on, adapter.isEnabled)) {
                        ToolResult(true, if (on) "Bluetooth включён." else "Bluetooth выключен.")
                    } else {
                        ToolResult(
                            false,
                            "Не удалось ${if (on) "включить" else "выключить"} Bluetooth: система не сменила состояние.",
                        )
                    }
                }
            }
        },
        object : ToolHandler {
            override val name = "flashlight_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val on = (arguments["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                        ?: return@withContext ToolResult(false, "Укажите on (true/false).")
                    val cm = context.getSystemService(CameraManager::class.java)
                    val cameraId = cm.cameraIdList.firstOrNull { it.let { id -> cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
                        ?: return@withContext ToolResult(false, "Фонарик недоступен на этом устройстве.")
                    // setTorchMode is fire-and-forget at the API level: the REAL torch state is
                    // only observable via CameraManager.TorchCallback.onTorchModeChanged. So we
                    // register a callback, request the desired state, and wait for the system to
                    // CONFIRM that the torch actually reached the requested state. We return
                    // success ONLY when the real state matches the request; otherwise an honest
                    // failure (never a nominal "done" that did not actually happen).
                    val confirmed = torchSetAndConfirm(cm, cameraId, on)
                    if (confirmed == on) {
                        ToolResult(true, if (on) "Фонарик включён." else "Фонарик выключен.")
                    } else {
                        ToolResult(false, "Не удалось ${if (on) "включить" else "выключить"} фонарик: система не подтвердила, что состояние реально изменилось.")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "storage_volumes"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    // Listing storage volumes needs no permission. StorageManager is the
                    // documented entry point:
                    // https://developer.android.com/reference/android/os/storage/StorageManager
                    val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                        ?: return@withContext ToolResult(false, "Служба хранилища (STORAGE_SERVICE) недоступна на этом устройстве.")
                    val volumes = sm.storageVolumes
                    if (volumes.isEmpty()) {
                        return@withContext ToolResult(false, "Система не вернула ни одного тома хранилища.")
                    }
                    val sb = StringBuilder()
                    sb.appendLine("Тома хранилища: ${volumes.size}")
                    volumes.forEachIndexed { index, volume ->
                        sb.appendLine()
                        sb.appendLine("${index + 1}) ${volume.getDescription(context)}")
                        sb.appendLine("  Основной: ${if (volume.isPrimary) "да" else "нет"}")
                        sb.appendLine("  Съёмный: ${if (volume.isRemovable) "да" else "нет"}")
                        sb.appendLine("  UUID: ${volume.uuid ?: "нет"}")
                        sb.appendLine("  Состояние: ${volume.state}")
                        // StorageVolume.getDirectory() exists only on API 30+ — never call it
                        // unguarded, or it would crash on API 26-29 devices. Guarded with
                        // Build.VERSION.SDK_INT (https://developer.android.com/reference/android/os/Build.VERSION#SDK_INT).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val dir = volume.directory?.absolutePath
                            sb.appendLine("  Корневой путь: ${dir ?: "недоступен"}")
                        } else {
                            sb.appendLine("  Корневой путь: недоступен (нужен Android 11 / API 30+)")
                        }
                    }
                    ToolResult(true, sb.toString().trim())
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "locale_app_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                val lang = (arguments["lang"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (lang.isEmpty()) {
                    return@withContext ToolResult(false, "Укажите lang — языковой тег, например ru или en-US.")
                }
                val mode = (arguments["mode"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                if (mode != null && mode != "system" && mode != "default") {
                    return@withContext ToolResult(false, "mode должен быть system или default (получено: «$mode»).")
                }
                // LocaleManager.setApplicationLocales is API 33+:
                // https://developer.android.com/reference/android/app/LocaleManager
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    return@withContext ToolResult(
                        false,
                        "Смена локали приложения через LocaleManager доступна на Android 13 (API 33) и выше; " +
                            "на этом устройстве API ${Build.VERSION.SDK_INT}.",
                    )
                }
                try {
                    val lm = context.getSystemService(LocaleManager::class.java)
                        ?: return@withContext ToolResult(false, "LocaleManager недоступен на устройстве.")
                    val target: LocaleList
                    val expectedEmpty: Boolean
                    if (mode == "system") {
                        // An empty locale list means "follow the system locale again".
                        target = LocaleList.getEmptyLocaleList()
                        expectedEmpty = true
                    } else {
                        target = LocaleList.forLanguageTags(lang)
                        expectedEmpty = false
                    }
                    lm.applicationLocales = target
                    // Read back what the system actually applied — never report a nominal success.
                    val applied = lm.applicationLocales
                    val appliedTags = if (applied.isEmpty) "(системная)" else applied.toLanguageTags()
                    when {
                        applied.isEmpty != expectedEmpty -> ToolResult(
                            false,
                            "Система не применила локаль: запрошено " +
                                "«${if (expectedEmpty) "системная" else lang}», " +
                                "текущая локаль приложения $appliedTags.",
                        )

                        expectedEmpty -> ToolResult(true, "Локаль приложения сброшена на системную.")
                        else -> ToolResult(true, "Локаль приложения изменена: $appliedTags.")
                    }
                } catch (e: IllegalArgumentException) {
                    ToolResult(
                        false,
                        "Система отклонила локаль «$lang»: " +
                            "${e.message ?: "локаль не поддерживается приложением"}.",
                    )
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка смены локали приложения: ${e.message}")
                }
            }
        },
    )

    /**
     * Requests [setTorchMode] and waits for the system's [CameraManager.TorchCallback] to confirm
     * that the torch of [cameraId] actually reached [target].
     *
     * Reference: https://developer.android.com/reference/android/hardware/camera2/CameraManager
     * - `setTorchMode(cameraId, enabled)` only *requests* the state; if it is applied successfully
     *   `TorchCallback.onTorchModeChanged` is invoked with the new state.
     * - When a callback is first registered it is immediately invoked with the CURRENT state of all
     *   torch-capable cameras, so we read the current state first and only wait for a real change if
     *   the torch is not already in the requested state.
     *
     * @return the confirmed torch state, or null if it could not be confirmed within the timeout.
     */
    private suspend fun torchSetAndConfirm(
        cm: CameraManager,
        cameraId: String,
        target: Boolean,
    ): Boolean? {
        val thread = HandlerThread("andharness-torch").apply { start() }
        val handler = Handler(thread.looper)
        var current: Boolean? = null
        val currentReady = CompletableDeferred<Unit>()
        val confirmed = CompletableDeferred<Boolean?>()
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id != cameraId) return
                if (current == null) {
                    current = enabled
                    currentReady.complete(Unit)
                } else {
                    confirmed.complete(enabled)
                }
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id != cameraId) return
                if (current == null) {
                    current = null
                    currentReady.complete(Unit)
                } else {
                    confirmed.complete(null)
                }
            }
        }
        return try {
            cm.registerTorchCallback(callback, handler)
            // Wait for the initial (current-state) callback so we know where the torch is now.
            withTimeoutOrNull(2000) { currentReady.await() } ?: return null
            val start = current
            if (start == null) return null // torch currently unavailable
            if (start == target) return start // already in the requested state
            // Not in the requested state yet: request it and wait for the system to confirm.
            var attempt = 0
            var reached: Boolean? = null
            while (attempt < TORCH_ATTEMPTS) {
                attempt++
                if (!confirmed.isCompleted) {
                    try {
                        cm.setTorchMode(cameraId, target)
                    } catch (e: Exception) {
                        return false
                    }
                }
                val result = withTimeoutOrNull(TORCH_CONFIRM_TIMEOUT_MS) { confirmed.await() }
                if (result != null) {
                    reached = result
                    break
                }
                // result == null → no confirmation within the timeout; loop again to retry.
            }
            reached
        } finally {
            cm.unregisterTorchCallback(callback)
            thread.quitSafely()
        }
    }

    /**
     * Opens a system settings screen from a non-activity context. Needs [Intent.FLAG_ACTIVITY_NEW_TASK]
     * because the handler runs off the activity (https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_NEW_TASK).
     * Returns true if the system accepted the intent, false otherwise.
     */
    private fun openSettingsPanel(action: String): Boolean = try {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        private const val TORCH_ATTEMPTS = 2
        private const val TORCH_CONFIRM_TIMEOUT_MS = 4_000L
    }
}
