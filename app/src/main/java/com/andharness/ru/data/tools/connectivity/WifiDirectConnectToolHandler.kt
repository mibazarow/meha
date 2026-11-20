package com.andharness.ru.data.tools.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `wifi_direct_connect`: connects to a Wi-Fi Direct peer and reports the resulting group. */
internal class WifiDirectConnectToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "wifi_direct_connect"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val address = (arguments["device_address"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (address.isEmpty()) {
            return@withContext ToolResult(false, "Укажите device_address (MAC-адрес пира Wi-Fi Direct).")
        }
        val wpsPin = (arguments["wps_pin"] as? JsonPrimitive)?.content
            ?.trim()?.takeIf { it.isNotEmpty() }
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        val p2p = context.getSystemService(WifiP2pManager::class.java)
            ?: return@withContext ToolResult(false, "Wi-Fi Direct недоступен на этом устройстве.")
        val channel = access.openP2pChannel(p2p)
            ?: return@withContext ToolResult(
                false,
                "Не удалось открыть канал Wi-Fi Direct (нет разрешений или служба недоступна).",
            )
        var info: WifiP2pInfo? = null
        var failureMessage: String? = null
        val signal = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        p2p.requestConnectionInfo(channel) { connectionInfo ->
                            if (connectionInfo.groupFormed) {
                                info = connectionInfo
                            } else {
                                failureMessage = "Соединение Wi-Fi Direct установлено, но группа не сформирована."
                            }
                            signal.complete(Unit)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            failureMessage = "Wi-Fi Direct (P2P) выключен — включите Wi-Fi и повторите."
                            signal.complete(Unit)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        try {
            @Suppress("DEPRECATION")
            val config = WifiP2pConfig().apply {
                deviceAddress = address
                if (wpsPin != null) {
                    wps.setup = WpsInfo.KEYPAD
                    wps.pin = wpsPin
                } else {
                    wps.setup = WpsInfo.PBC
                }
            }
            @Suppress("DEPRECATION")
            p2p.connect(
                channel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit

                    override fun onFailure(reason: Int) {
                        failureMessage = "Не удалось начать подключение Wi-Fi Direct (код $reason)."
                        signal.complete(Unit)
                    }
                },
            )
            withTimeoutOrNull(P2P_CONNECT_TIMEOUT_MS) { signal.await() }
            val formed = info
            when {
                formed != null -> {
                    val ownerAddress = formed.groupOwnerAddress?.hostAddress ?: "неизвестно"
                    ToolResult(
                        true,
                        "Соединение Wi-Fi Direct установлено с $address.\n" +
                            "Роль: ${if (formed.isGroupOwner) "владелец группы (Group Owner)" else "клиент"}.\n" +
                            "Адрес владельца группы: $ownerAddress",
                    )
                }

                failureMessage != null -> ToolResult(false, failureMessage ?: "Не удалось подключиться.")
                else -> ToolResult(
                    false,
                    "Таймаут ${P2P_CONNECT_TIMEOUT_MS / 1000} с: соединение с $address не подтверждено.",
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка подключения Wi-Fi Direct: ${e.message}")
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // Receiver may already be unregistered; ignore.
            }
        }
    }

    private companion object {
        /** Bounded wait for a Wi-Fi Direct connection to be established. */
        private const val P2P_CONNECT_TIMEOUT_MS = 30_000L
    }
}
