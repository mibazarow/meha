package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.wifi.WifiManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `wifi_info`: SSID and IPv4 of the current Wi-Fi connection. */
internal class WifiInfoToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "wifi_info"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
                ?: return@withContext ToolResult(false, "Не удалось получить сведения о Wi-Fi.")
            val ssid = info.ssid
            val sb = StringBuilder()
            if (ssid == null || ssid == "<unknown ssid>") {
                sb.appendLine("SSID: недоступен (нужно разрешение на геолокацию и включённая геолокация).")
            } else {
                sb.appendLine("SSID: $ssid")
            }
            val ip = info.ipAddress
            if (ip != 0) {
                val ipStr = "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"
                sb.appendLine("IP: $ipStr")
            }
            ToolResult(true, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
