package com.andharness.ru.data.tools.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Builds one [ToolHandler] per VPN tool: [vpn_status], [vpn_prepare].
 *
 * Official references (consulted before writing this code):
 * - `VpnService.prepare(Context)` returns `null` when the app is already prepared / the user has
 *   already consented, otherwise an `Intent` to a system activity that must be launched to ask
 *   for consent. The app must call `prepare` before it may use the other `VpnService` methods:
 *   https://developer.android.com/develop/connectivity/vpn
 *   https://developer.android.com/reference/android/net/VpnService#prepare(android.content.Context)
 * - Reading VPN state via `ConnectivityManager` / `NetworkCapabilities.TRANSPORT_VPN`:
 *   https://developer.android.com/develop/connectivity/network-ops/reading-network-state
 *   https://developer.android.com/reference/android/net/NetworkCapabilities#TRANSPORT_VPN
 * - SDK sources cross-check: `~/Library/Android/sdk/sources/android-37.0/android/net/VpnService.java`
 *
 * Honesty rules: [vpn_prepare] only *requests* the system consent dialog and never claims that
 * the user accepted it. There is intentionally no `vpn_connect`: a real tunnel requires a remote
 * server and a userspace TCP/IP stack plus a foreground `VpnService`, which is out of scope for
 * these tools. Nothing here fakes a tunnel.
 */
class VpnToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(status(), prepare())

    private fun status(): ToolHandler = object : ToolHandler {
        override val name = "vpn_status"

        // `ConnectivityManager.getAllNetworks()` is marked deprecated in newer SDKs, but it is still
        // the only way to enumerate every network (and thus find VPN transports); the replacement
        // NetworkCallback API only reports the networks a request matches.
        @Suppress("DEPRECATION")
        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            try {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                    ?: return@withContext ToolResult(false, "ConnectivityManager недоступен на устройстве.")
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }

                val transports = mutableListOf<String>()
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("VPN")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WIFI")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("CELLULAR")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ETHERNET")
                }
                val vpnOnActive = "VPN" in transports
                val vpnNetworkCount = cm.allNetworks.count { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                // `prepare()` returns null once the app already holds the user's consent; it does not
                // open a dialog here, so it is safe to call from a background thread.
                val consentPrepared = try {
                    VpnService.prepare(context) == null
                } catch (_: Exception) {
                    null
                }

                ToolResult(
                    true,
                    VpnStatusFormatter.format(
                        VpnStatusInfo(
                            activeNetworkPresent = active != null,
                            transports = transports,
                            vpnOnActive = vpnOnActive,
                            vpnNetworkCount = vpnNetworkCount,
                            consentPrepared = consentPrepared,
                        )
                    )
                )
            } catch (e: Exception) {
                ToolResult(false, "Ошибка чтения состояния VPN: ${e.message}")
            }
        }
    }

    private fun prepare(): ToolHandler = object : ToolHandler {
        override val name = "vpn_prepare"

        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            try {
                val consentIntent = VpnService.prepare(context)
                if (consentIntent == null) {
                    return@withContext ToolResult(
                        true,
                        "Согласие на VPN уже получено: VpnService.prepare() вернул null — приложение " +
                            "уже подготовлено системой. Повторный диалог не требуется."
                    )
                }
                consentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(consentIntent)
                ToolResult(
                    true,
                    "Отправлен системный запрос согласия на VPN (VpnService.prepare). Приложение НЕ " +
                        "утверждает, что пользователь согласился: решение принимается в системном диалоге, " +
                        "и результат здесь не подтверждён. Диалог может не отобразиться, если приложение " +
                        "не на переднем плане. Полноценный туннель не создаётся: для него нужен удалённый " +
                        "сервер и userspace TCP/IP-стек, поэтому vpn_connect намеренно отсутствует."
                )
            } catch (e: Exception) {
                ToolResult(false, "Ошибка запроса согласия на VPN: ${e.message}")
            }
        }
    }
}
