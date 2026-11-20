package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** `ble_scan`: scans for BLE devices for a bounded time and lists them by RSSI. */
internal class BleScanToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {

    /** One BLE device observed during a scan; [rssi] keeps the latest observation. */
    private class BleScanHit(
        val address: String,
        val name: String?,
        @Volatile var rssi: Int,
        val firstSeenElapsedMs: Long,
    )

    override val name = "ble_scan"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val durationSec = ((arguments["duration_sec"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 8)
            .coerceIn(1, 30)
        val nameFilter = (arguments["name_filter"] as? JsonPrimitive)?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
        try {
            // Permission split (https://developer.android.com/develop/connectivity/bluetooth/bt-permissions):
            // API 31+ needs BLUETOOTH_SCAN to discover and BLUETOOTH_CONNECT to read the
            // address/name; API <= 30 needs the runtime location permission instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!access.hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    return@withContext ToolResult(false, "PERMISSION_REQUIRED:${Manifest.permission.BLUETOOTH_SCAN}")
                }
                if (!access.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    return@withContext ToolResult(false, "PERMISSION_REQUIRED:${Manifest.permission.BLUETOOTH_CONNECT}")
                }
            } else if (!access.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return@withContext ToolResult(false, "PERMISSION_REQUIRED:${Manifest.permission.ACCESS_FINE_LOCATION}")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@withContext ToolResult(false, "Bluetooth недоступен на устройстве.")
            if (!adapter.isEnabled) {
                return@withContext ToolResult(
                    false,
                    "Bluetooth выключен — включите его и повторите сканирование."
                )
            }
            val scanner = adapter.bluetoothLeScanner
                ?: return@withContext ToolResult(false, "Сканер BLE недоступен на устройстве.")
            val found = ConcurrentHashMap<String, BleScanHit>()
            val scanError = CompletableDeferred<String>()
            val startedAt = SystemClock.elapsedRealtime()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val address = try {
                        device.address
                    } catch (_: SecurityException) {
                        return
                    } ?: return
                    val advertisedName = try {
                        result.scanRecord?.deviceName
                    } catch (_: SecurityException) {
                        null
                    }
                    val name = advertisedName ?: try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    }
                    if (nameFilter != null && name?.contains(nameFilter, ignoreCase = true) != true) return
                    found.compute(address) { _, existing ->
                        if (existing == null) {
                            BleScanHit(address, name, result.rssi, SystemClock.elapsedRealtime() - startedAt)
                        } else {
                            existing.rssi = result.rssi
                            if (existing.name.isNullOrBlank() && !name.isNullOrBlank()) {
                                BleScanHit(address, name, result.rssi, existing.firstSeenElapsedMs)
                            } else {
                                existing
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    scanError.complete(bleScanErrorMessage(errorCode))
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val failure = try {
                @Suppress("MissingPermission")
                scanner.startScan(null, settings, callback)
                // Scan for the requested duration; onScanFailed ends the wait early.
                withTimeoutOrNull(durationSec * 1000L) { scanError.await() }
            } finally {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                    // Scan may already have stopped; ignore.
                }
            }
            if (failure != null) {
                return@withContext ToolResult(false, failure)
            }
            val hits = found.values.sortedByDescending { it.rssi }
            if (hits.isEmpty()) {
                val filterNote = if (nameFilter != null) " (фильтр по имени: «$nameFilter»)" else ""
                return@withContext ToolResult(
                    true,
                    "BLE-устройства не найдены за $durationSec с$filterNote."
                )
            }
            val lines = hits.joinToString("\n") { hit ->
                val name = hit.name?.takeIf { it.isNotBlank() } ?: "<без имени>"
                val seen = String.format(Locale.US, "%.1f", hit.firstSeenElapsedMs / 1000.0)
                "$name | ${hit.address} | RSSI ${hit.rssi} dBm | впервые через $seen с"
            }
            ToolResult(
                true,
                "Найдено BLE-устройств: ${hits.size} за $durationSec с (отсортировано по RSSI)\n$lines"
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    /**
     * Human-readable reason for a [ScanCallback.onScanFailed] error code.
     * https://developer.android.com/reference/android/bluetooth/le/ScanCallback
     */
    private fun bleScanErrorMessage(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "сканирование уже запущено (код $errorCode)"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "не удалось зарегистрировать сканирование в системе (код $errorCode)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "внутренняя ошибка Bluetooth-стека (код $errorCode)"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "сканирование BLE не поддерживается (код $errorCode)"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
            "не хватает аппаратных ресурсов для сканирования (код $errorCode)"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
            "слишком частые сканирования — повторите позже (код $errorCode)"
        else -> "не удалось запустить сканирование (код $errorCode)"
    }
}
