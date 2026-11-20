package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** `wifi_rtt_range`: measures distance to a visible access point via Wi-Fi RTT (API 28+). */
internal class WifiRttRangeToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "wifi_rtt_range"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // WifiRttManager was added in API 28 (Android 9):
        // https://developer.android.com/develop/connectivity/wifi/wifi-rtt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return@withContext ToolResult(
                false,
                "Измерение RTT доступно на Android 9 (API 28) и выше; " +
                    "на этом устройстве API ${Build.VERSION.SDK_INT}.",
            )
        }
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        val timeoutSec = ((arguments["timeout_sec"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 10)
            .coerceIn(3, 60)
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return@withContext ToolResult(false, "WifiManager недоступен на устройстве.")
        val requestedBssid = (arguments["bssid"] as? JsonPrimitive)?.content
            ?.trim()?.takeIf { it.isNotEmpty() }
        val currentBssid = if (requestedBssid == null) {
            try {
                @Suppress("DEPRECATION", "MissingPermission")
                wm.connectionInfo?.bssid
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val bssid = requestedBssid ?: currentBssid
        if (bssid.isNullOrBlank() || bssid.equals("02:00:00:00:00:00", ignoreCase = true)) {
            return@withContext ToolResult(
                false,
                "Не удалось определить BSSID точки доступа. Подключитесь к Wi-Fi или укажите bssid явно.",
            )
        }
        // RangingRequest.Builder only accepts a ScanResult through the public API, so the
        // target access point must be visible in the latest Wi-Fi scan results.
        @Suppress("DEPRECATION", "MissingPermission")
        val accessPoint = try {
            wm.startScan()
            wm.scanResults.firstOrNull { it.BSSID?.equals(bssid, ignoreCase = true) == true }
        } catch (_: SecurityException) {
            return@withContext ToolResult(
                false,
                "PERMISSION_REQUIRED:${Manifest.permission.NEARBY_WIFI_DEVICES}",
            )
        } catch (_: Exception) {
            null
        }
        if (accessPoint == null) {
            return@withContext ToolResult(
                false,
                "Точка доступа $bssid не найдена в результатах сканирования Wi-Fi: RTT измеряется " +
                    "только для видимых точек доступа (нужны разрешение и включённая геолокация).",
            )
        }
        val rtt = context.getSystemService(WifiRttManager::class.java)
            ?: return@withContext ToolResult(false, "WifiRttManager недоступен на этом устройстве.")
        if (!rtt.isAvailable) {
            return@withContext ToolResult(
                false,
                "Измерение RTT недоступно (нет поддержки Wi-Fi RTT или Wi-Fi выключен).",
            )
        }
        var results: List<RangingResult>? = null
        var failureMessage: String? = null
        val signal = CompletableDeferred<Unit>()
        val callback = object : RangingResultCallback() {
            override fun onRangingResults(rangingResults: List<RangingResult>) {
                results = rangingResults
                signal.complete(Unit)
            }

            override fun onRangingFailure(code: Int) {
                failureMessage = "Ошибка RTT-измерения (код $code)."
                signal.complete(Unit)
            }
        }
        try {
            val request = RangingRequest.Builder().addAccessPoint(accessPoint).build()
            rtt.startRanging(request, context.mainExecutor, callback)
            withTimeoutOrNull(timeoutSec * 1000L) { signal.await() }
                ?: return@withContext ToolResult(
                    false,
                    "Таймаут $timeoutSec с: точка доступа не ответила на RTT-запрос.",
                )
        } catch (_: SecurityException) {
            return@withContext ToolResult(
                false,
                "PERMISSION_REQUIRED:${Manifest.permission.NEARBY_WIFI_DEVICES}",
            )
        } catch (e: Exception) {
            return@withContext ToolResult(false, "Ошибка RTT-измерения: ${e.message}")
        }
        failureMessage?.let { return@withContext ToolResult(false, it) }
        val list = results.orEmpty()
        if (list.isEmpty()) {
            return@withContext ToolResult(false, "Система не вернула результатов RTT.")
        }
        val succeeded = list.any { it.status == RangingResult.STATUS_SUCCESS }
        val lines = list.joinToString("\n") { rangingResultLine(it, bssid) }
        ToolResult(succeeded, "Результаты Wi-Fi RTT для $bssid:\n$lines")
    }

    /**
     * One human-readable line per [RangingResult]; never fabricates a distance when the responder
     * failed or does not support 802.11mc.
     * https://developer.android.com/develop/connectivity/wifi/wifi-rtt
     */
    private fun rangingResultLine(result: RangingResult, fallbackBssid: String): String {
        val mac = try {
            result.macAddress?.toString()
        } catch (_: Exception) {
            null
        } ?: fallbackBssid
        return when (result.status) {
            RangingResult.STATUS_SUCCESS -> {
                val distanceM = String.format(Locale.US, "%.2f", result.distanceMm / 1000.0)
                val stdDevM = String.format(Locale.US, "%.2f", result.distanceStdDevMm / 1000.0)
                "$mac | дистанция $distanceM м (± $stdDevM м)"
            }

            RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC ->
                "$mac | точка доступа не поддерживает 802.11mc (RTT невозможен)"

            else -> "$mac | измерение не удалось (статус ${result.status})"
        }
    }
}
