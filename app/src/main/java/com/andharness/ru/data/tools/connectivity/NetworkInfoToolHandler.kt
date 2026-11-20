package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.Inet4Address

/** `network_info`: type, internet validation, interface and IPv4 of the active network. */
internal class NetworkInfoToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "network_info"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
                ?: return@withContext ToolResult(true, "Активного сетевого подключения нет.")
            val caps = cm.getNetworkCapabilities(network)
            val type = when {
                caps == null -> "неизвестно"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "другое"
            }
            val valid = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val sb = StringBuilder()
            sb.appendLine("Тип: $type")
            sb.appendLine("Интернет: ${if (valid) "есть" else "нет/не подтверждён"}")
            val link = cm.getLinkProperties(network)
            link?.let { lp ->
                lp.interfaceName?.let { sb.appendLine("Интерфейс: $it") }
                val ip = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
                if (ip != null) sb.appendLine("IP: $ip")
            }
            ToolResult(true, sb.toString().trim())
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
