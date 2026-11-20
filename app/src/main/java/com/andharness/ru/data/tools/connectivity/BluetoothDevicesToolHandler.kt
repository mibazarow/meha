package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `bluetooth_devices`: bonded Bluetooth devices (name + address). */
internal class BluetoothDevicesToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "bluetooth_devices"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return@withContext ToolResult(false, "Нет разрешения BLUETOOTH_CONNECT.")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@withContext ToolResult(false, "Bluetooth недоступен на устройстве.")
            if (!adapter.isEnabled) {
                return@withContext ToolResult(true, "Bluetooth выключен.")
            }
            @Suppress("MissingPermission")
            val bonded = adapter.bondedDevices
            if (bonded.isNullOrEmpty()) {
                ToolResult(true, "Сопряжённых устройств нет.")
            } else {
                ToolResult(true, bonded.joinToString("\n") { "${it.name ?: "?"} | ${it.address}" })
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
