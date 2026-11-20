package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `ble_connect`: connects to a BLE device and lists its GATT services/characteristics. */
internal class BleConnectToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "ble_connect"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val address = (arguments["address"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (address.isEmpty()) {
            return@withContext ToolResult(false, "Укажите address (MAC-адрес BLE-устройства).")
        }
        val durationSec = ((arguments["duration_sec"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 10)
            .coerceIn(1, 30)
        var gatt: BluetoothGatt? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !access.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                return@withContext ToolResult(false, "PERMISSION_REQUIRED:${Manifest.permission.BLUETOOTH_CONNECT}")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@withContext ToolResult(false, "Bluetooth недоступен на устройстве.")
            if (!adapter.isEnabled) {
                return@withContext ToolResult(
                    false,
                    "Bluetooth выключен — включите его и повторите подключение."
                )
            }
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                return@withContext ToolResult(false, "Некорректный MAC-адрес: $address")
            }
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                return@withContext ToolResult(false, "Некорректный MAC-адрес: $address")
            }
            val discovered = CompletableDeferred<BluetoothGatt>()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        discovered.completeExceptionally(
                            IllegalStateException("ошибка подключения (GATT-статус $status)")
                        )
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            if (!g.discoverServices()) {
                                discovered.completeExceptionally(
                                    IllegalStateException("не удалось запустить обнаружение сервисов")
                                )
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> discovered.completeExceptionally(
                            IllegalStateException("устройство отключилось")
                        )
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        discovered.complete(g)
                    } else {
                        discovered.completeExceptionally(
                            IllegalStateException("обнаружение сервисов не удалось (GATT-статус $status)")
                        )
                    }
                }
            }
            @Suppress("MissingPermission")
            val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: return@withContext ToolResult(false, "Не удалось начать подключение (connectGatt вернул null).")
            gatt = connection
            val connected = try {
                withTimeoutOrNull(durationSec * 1000L) { discovered.await() }
            } catch (e: Exception) {
                return@withContext ToolResult(
                    false,
                    "Не удалось подключиться к $address: ${e.message ?: "неизвестная ошибка"}"
                )
            }
            if (connected == null) {
                return@withContext ToolResult(
                    false,
                    "Таймаут подключения к $address за $durationSec с (устройство не отвечает или недоступно)."
                )
            }
            val deviceName = try {
                connected.device.name
            } catch (_: SecurityException) {
                null
            }?.takeIf { it.isNotBlank() } ?: "<без имени>"
            val services = connected.services.orEmpty()
            val sb = StringBuilder()
            sb.appendLine("Подключено: $deviceName | $address")
            sb.appendLine("Сервисы GATT: ${services.size}")
            services.forEach { service ->
                sb.appendLine(
                    "- ${service.uuid} (${serviceTypeName(service)}, характеристик: " +
                        "${service.characteristics.size})"
                )
                service.characteristics.forEach { characteristic ->
                    sb.appendLine("    · ${characteristic.uuid} | ${gattPropertyNames(characteristic.properties)}")
                }
            }
            ToolResult(true, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        } finally {
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
                // Already disconnected; ignore.
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
                // Already closed; ignore.
            }
        }
    }

    /** `primary` / `secondary` GATT service type. https://developer.android.com/reference/android/bluetooth/BluetoothGattService */
    private fun serviceTypeName(service: BluetoothGattService): String =
        if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "primary" else "secondary"

    /**
     * Human-readable GATT characteristic properties bitmask.
     * https://developer.android.com/reference/android/bluetooth/BluetoothGattCharacteristic#getProperties()
     */
    private fun gattPropertyNames(properties: Int): String {
        val names = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) names.add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names.add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names.add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) names.add("SIGNED_WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) names.add("EXTENDED_PROPS")
        return if (names.isEmpty()) "нет" else names.joinToString(", ")
    }
}
