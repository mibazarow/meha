package com.andharness.ru.data.tools.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `wifi_direct_discover`: discovers Wi-Fi Direct peers for a bounded time. */
internal class WifiDirectDiscoverToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "wifi_direct_discover"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val timeoutSec = ((arguments["timeout_sec"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 15)
            .coerceIn(5, 60)
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        val p2p = context.getSystemService(WifiP2pManager::class.java)
            ?: return@withContext ToolResult(false, "Wi-Fi Direct недоступен на этом устройстве.")
        val channel = access.openP2pChannel(p2p)
            ?: return@withContext ToolResult(
                false,
                "Не удалось открыть канал Wi-Fi Direct (нет разрешений или служба недоступна).",
            )
        var peers: List<WifiP2pDevice> = emptyList()
        var failureMessage: String? = null
        val signal = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        p2p.requestPeers(channel) { deviceList ->
                            peers = deviceList.deviceList.toList()
                            signal.complete(Unit)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            failureMessage = "Wi-Fi Direct (P2P) выключен — включите Wi-Fi и повторите поиск."
                            signal.complete(Unit)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        try {
            @Suppress("DEPRECATION")
            p2p.discoverPeers(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit

                    override fun onFailure(reason: Int) {
                        failureMessage = "Не удалось начать поиск Wi-Fi Direct (код $reason)."
                        signal.complete(Unit)
                    }
                },
            )
            withTimeoutOrNull(timeoutSec * 1000L) { signal.await() }
            val failure = failureMessage
            when {
                failure != null -> ToolResult(false, failure)
                peers.isEmpty() -> ToolResult(
                    true,
                    "Поиск завершён за $timeoutSec с: пиры Wi-Fi Direct не найдены.",
                )

                else -> ToolResult(
                    true,
                    "Найдено пиров Wi-Fi Direct: ${peers.size}\n" + peers.joinToString("\n") { device ->
                        val deviceName = device.deviceName?.takeIf { it.isNotBlank() } ?: "<без имени>"
                        "$deviceName | ${device.deviceAddress} | ${p2pStatusName(device.status)}"
                    },
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка поиска Wi-Fi Direct: ${e.message}")
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Receiver may already be unregistered; ignore.
            }
        }
    }

    /**
     * Human-readable Wi-Fi Direct peer status.
     * https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pDevice
     */
    private fun p2pStatusName(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "доступен"
        WifiP2pDevice.INVITED -> "приглашён"
        WifiP2pDevice.CONNECTED -> "подключён"
        WifiP2pDevice.FAILED -> "ошибка подключения"
        WifiP2pDevice.UNAVAILABLE -> "недоступен"
        else -> "статус $status"
    }
}
