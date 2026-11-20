package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `network_detail`: transports, metered state, validation, bandwidth and VPN of the default network. */
internal class NetworkDetailToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "network_detail"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
                ?: return@withContext ToolResult(false, "ConnectivityManager недоступен на устройстве.")
            val network = cm.activeNetwork
                ?: return@withContext ToolResult(true, "Активной сети по умолчанию нет.")
            val caps = cm.getNetworkCapabilities(network)
                ?: return@withContext ToolResult(true, "Сведения о сети недоступны.")

            val transports = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ETHERNET")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN")

            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val downKbps = caps.linkDownstreamBandwidthKbps
            val upKbps = caps.linkUpstreamBandwidthKbps

            val sb = StringBuilder()
            sb.appendLine("Сеть по умолчанию: активна")
            sb.appendLine(
                "Транспорт: " +
                    (if (transports.isEmpty()) "неизвестно" else transports.joinToString(", "))
            )
            sb.appendLine("Metered (лимитированный трафик): ${if (cm.isActiveNetworkMetered) "да" else "нет"}")
            sb.appendLine("Интернет подтверждён (NET_CAPABILITY_VALIDATED): ${if (validated) "да" else "нет"}")
            sb.appendLine("Полоса пропускания: ↓ $downKbps kbps / ↑ $upKbps kbps")
            sb.appendLine("VPN активен: ${if (vpnActive) "да" else "нет"}")
            ToolResult(true, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
