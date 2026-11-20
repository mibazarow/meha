package com.andharness.ru.data.tools.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds one [ToolHandler] per USB-host tool: [usb_list], [usb_request_permission], [usb_info].
 *
 * Official references (consulted before writing this code):
 * - USB host overview: `UsbManager.getDeviceList()`, `UsbManager.hasPermission(UsbDevice)`,
 *   `UsbManager.requestPermission(UsbDevice, PendingIntent)`, `UsbManager.openDevice(UsbDevice)`,
 *   and the `UsbManager.EXTRA_DEVICE` / `UsbManager.EXTRA_PERMISSION_GRANTED` result extras:
 *   https://developer.android.com/develop/connectivity/usb/host
 * - `UsbManager` API reference:
 *   https://developer.android.com/reference/android/hardware/usb/UsbManager
 * - `UsbDevice` / `UsbInterface` / `UsbEndpoint` metadata accessors and `UsbConstants`:
 *   https://developer.android.com/reference/android/hardware/usb/UsbDevice
 *   https://developer.android.com/reference/android/hardware/usb/UsbConstants
 * - Runtime receiver registration flags (API 33+ requires `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED`
 *   when registering for non-system broadcasts):
 *   https://developer.android.com/develop/background-work/background-tasks/broadcasts
 * - SDK sources cross-check: `~/Library/Android/sdk/sources/android-37.0/android/hardware/usb/`
 *
 * Honesty rules: listing needs no permission; opening requires the system consent dialog result.
 * [usb_info] opens the device and reports interfaces/endpoints only — it does NOT claim an
 * interface or transfer data, because that requires a device-specific protocol that is out of
 * scope here.
 */
class UsbToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(list(), requestPermission(), info())

    private fun list(): ToolHandler = object : ToolHandler {
        override val name = "usb_list"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            try {
                val usbManager = context.getSystemService(UsbManager::class.java)
                    ?: return@withContext ToolResult(false, "UsbManager недоступен на устройстве.")
                val devices = usbManager.deviceList ?: emptyMap()
                if (devices.isEmpty()) {
                    return@withContext ToolResult(
                        true,
                        "Подключённых USB-устройств (OTG) нет. Список пуст."
                    )
                }
                val lines = devices.values
                    .sortedBy { it.deviceId }
                    .map { device ->
                        val permission = if (usbManager.hasPermission(device)) "доступ есть" else "доступа нет"
                        buildString {
                            append("${safeName(device.manufacturerName)} ${safeName(device.productName)}")
                            appendLine()
                            append(
                                "  vendor=${hexId(device.vendorId)} product=${hexId(device.productId)} " +
                                    "deviceId=${device.deviceId} class=${classLabel(device.deviceClass)} " +
                                    "(${permission})"
                            )
                        }
                    }
                ToolResult(true, "USB-устройств: ${devices.size}\n" + lines.joinToString("\n"))
            } catch (e: SecurityException) {
                ToolResult(false, "Нет доступа к списку USB-устройств: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка получения списка USB-устройств: ${e.message}")
            }
        }
    }

    private fun requestPermission(): ToolHandler = object : ToolHandler {
        override val name = "usb_request_permission"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val vendorId = parseId(arguments, "vendor_id")
                ?: return@withContext ToolResult(false, "Укажите vendor_id — идентификатор производителя (число).")
            val productId = parseId(arguments, "product_id")
                ?: return@withContext ToolResult(false, "Укажите product_id — идентификатор продукта (число).")
            try {
                val usbManager = context.getSystemService(UsbManager::class.java)
                    ?: return@withContext ToolResult(false, "UsbManager недоступен на устройстве.")
                val device = usbManager.deviceList.values.firstOrNull {
                    it.vendorId == vendorId && it.productId == productId
                } ?: return@withContext ToolResult(
                    false,
                    "USB-устройство ${hexId(vendorId)}:${hexId(productId)} не найдено среди подключённых. " +
                        "Убедитесь, что оно подключено через OTG (см. usb_list)."
                )
                if (usbManager.hasPermission(device)) {
                    return@withContext ToolResult(
                        true,
                        "Доступ к USB-устройству ${hexId(vendorId)}:${hexId(productId)} уже предоставлен."
                    )
                }

                val granted = CompletableDeferred<Boolean>()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, receivedIntent: Intent?) {
                        val resultIntent = receivedIntent ?: return
                        if (resultIntent.action != ACTION_USB_PERMISSION) return
                        val returnedDevice = resultIntent.usbDevice()
                        if (returnedDevice != null && returnedDevice.deviceId != device.deviceId) return
                        granted.complete(
                            resultIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        )
                    }
                }
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }
                try {
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    usbManager.requestPermission(device, pendingIntent)
                    val outcome = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { granted.await() }
                    when (outcome) {
                        true -> ToolResult(
                            true,
                            "Доступ к USB-устройству ${hexId(vendorId)}:${hexId(productId)} предоставлен пользователем."
                        )
                        false -> ToolResult(
                            false,
                            "Пользователь отклонил доступ к USB-устройству ${hexId(vendorId)}:${hexId(productId)}."
                        )
                        null -> ToolResult(
                            false,
                            "Ответ на запрос доступа к USB-устройству не получен за " +
                                "${PERMISSION_TIMEOUT_MS / 1000} с: пользователь не ответил или системный диалог " +
                                "не был показан (приложение могло быть в фоне). Разрешение не выдано."
                        )
                    }
                } finally {
                    runCatching { context.unregisterReceiver(receiver) }
                }
            } catch (e: Exception) {
                ToolResult(false, "Ошибка запроса доступа к USB-устройству: ${e.message}")
            }
        }
    }

    private fun info(): ToolHandler = object : ToolHandler {
        override val name = "usb_info"

        @Suppress("DEPRECATION")
        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val vendorId = parseId(arguments, "vendor_id")
                ?: return@withContext ToolResult(false, "Укажите vendor_id — идентификатор производителя (число).")
            val productId = parseId(arguments, "product_id")
                ?: return@withContext ToolResult(false, "Укажите product_id — идентификатор продукта (число).")
            try {
                val usbManager = context.getSystemService(UsbManager::class.java)
                    ?: return@withContext ToolResult(false, "UsbManager недоступен на устройстве.")
                val device = usbManager.deviceList.values.firstOrNull {
                    it.vendorId == vendorId && it.productId == productId
                } ?: return@withContext ToolResult(
                    false,
                    "USB-устройство ${hexId(vendorId)}:${hexId(productId)} не найдено среди подключённых."
                )
                if (!usbManager.hasPermission(device)) {
                    return@withContext ToolResult(
                        false,
                        "Нет доступа к USB-устройству ${hexId(vendorId)}:${hexId(productId)}. " +
                            "Сначала вызовите usb_request_permission."
                    )
                }

                val connection = usbManager.openDevice(device)
                try {
                    val sb = StringBuilder()
                    sb.appendLine("USB-устройство ${hexId(device.vendorId)}:${hexId(device.productId)}")
                    sb.appendLine("Название: ${safeName(device.manufacturerName)} ${safeName(device.productName)}")
                    sb.appendLine(
                        "Класс устройства: ${classLabel(device.deviceClass)} " +
                            "(subclass 0x${device.deviceSubclass.toString(16)}, protocol 0x${device.deviceProtocol.toString(16)})"
                    )
                    sb.appendLine("Интерфейсов: ${device.interfaceCount}")
                    for (index in 0 until device.interfaceCount) {
                        val usbInterface = device.getInterface(index)
                        sb.appendLine(
                            "  Интерфейс ${usbInterface.id}: class=${classLabel(usbInterface.interfaceClass)} " +
                                "subclass=0x${usbInterface.interfaceSubclass.toString(16)} " +
                                "protocol=0x${usbInterface.interfaceProtocol.toString(16)}, " +
                                "эндпойнтов ${usbInterface.endpointCount}"
                        )
                        for (endpointIndex in 0 until usbInterface.endpointCount) {
                            val endpoint = usbInterface.getEndpoint(endpointIndex)
                            val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                            sb.appendLine(
                                "    Эндпойнт 0x${endpoint.address.toString(16)}: $direction, " +
                                    "тип ${transferTypeLabel(endpoint.type)}, " +
                                    "maxPacket=${endpoint.maxPacketSize}, interval=${endpoint.interval}"
                            )
                        }
                    }
                    sb.appendLine()
                    sb.appendLine(
                        if (connection != null) {
                            "Устройство открыто (UsbDeviceConnection), но интерфейс НЕ захвачен и обмен данными не выполняется: " +
                                "для чтения/записи нужен протокол конкретного устройства."
                        } else {
                            "Метаданные прочитаны, но открыть устройство (openDevice) не удалось — обмен данными недоступен."
                        }
                    )
                    ToolResult(true, sb.toString().trim())
                } finally {
                    connection?.close()
                }
            } catch (e: SecurityException) {
                ToolResult(false, "Нет доступа к USB-устройству: ${e.message}")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка чтения сведений о USB-устройстве: ${e.message}")
            }
        }
    }

    /** Reads a decimal or `0x`-prefixed hexadecimal hardware id from [arguments]. */
    private fun parseId(arguments: JsonObject, key: String): Int? {
        val raw = (arguments[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toInt(16) else raw.toInt()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun hexId(value: Int): String = "0x${value.toString(16).padStart(4, '0')}"

    private fun safeName(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "(без названия)"

    private fun classLabel(code: Int): String = when (code) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "PER_INTERFACE"
        UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
        UsbConstants.USB_CLASS_COMM -> "COMM"
        UsbConstants.USB_CLASS_HID -> "HID"
        UsbConstants.USB_CLASS_PHYSICA -> "PHYSICAL"
        UsbConstants.USB_CLASS_STILL_IMAGE -> "STILL_IMAGE"
        UsbConstants.USB_CLASS_PRINTER -> "PRINTER"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
        UsbConstants.USB_CLASS_HUB -> "HUB"
        UsbConstants.USB_CLASS_CDC_DATA -> "CDC_DATA"
        UsbConstants.USB_CLASS_CSCID -> "CSCID"
        UsbConstants.USB_CLASS_CONTENT_SEC -> "CONTENT_SEC"
        UsbConstants.USB_CLASS_VIDEO -> "VIDEO"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "WIRELESS_CONTROLLER"
        UsbConstants.USB_CLASS_MISC -> "MISC"
        UsbConstants.USB_CLASS_APP_SPEC -> "APP_SPEC"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC"
        else -> "0x${code.toString(16)}"
    }

    private fun transferTypeLabel(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        else -> "0x${type.toString(16)}"
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.andharness.ru.USB_PERMISSION"
        const val PERMISSION_TIMEOUT_MS = 30_000L
    }
}

/**
 * Extracts the [UsbDevice] from a USB permission result intent. The typed `getParcelableExtra`
 * overload used on API 33+ comes from `Intent`; the older overload is deprecated but required
 * below API 33 (minSdk 26). https://developer.android.com/reference/android/content/Intent
 */
@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
    }
