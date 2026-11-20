package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.wifi.WifiManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `wifi_scan`: triggers a Wi-Fi scan and lists the strongest visible networks. */
internal class WifiScanToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "wifi_scan"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            if (!wm.isWifiEnabled) {
                return@withContext ToolResult(true, "Wi-Fi выключен.")
            }
            @Suppress("DEPRECATION")
            wm.startScan()
            val results = wm.scanResults
            if (results.isNullOrEmpty()) {
                return@withContext ToolResult(
                    true,
                    "Сети не найдены (нужны разрешение на геолокацию и включённая геолокация; сканирование может быть ограничено системой)."
                )
            }
            val lines = results
                .distinctBy { it.SSID.ifBlank { it.BSSID } }
                .sortedByDescending { it.level }
                .take(30)
                .joinToString("\n") { r ->
                    val ssid = r.SSID.ifBlank { "<скрытая>" }
                    "$ssid | ${r.level} dBm | ${r.capabilities.take(40)}"
                }
            ToolResult(true, lines)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
