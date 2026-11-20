package com.andharness.ru.data.tools.connectivity

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `wifi_connect_specifier`: requests a Wi-Fi network through `WifiNetworkSpecifier` (API 29+). */
internal class WifiConnectSpecifierToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "wifi_connect_specifier"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val ssid = (arguments["ssid"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (ssid.isEmpty()) {
            return@withContext ToolResult(false, "Укажите ssid сети Wi-Fi.")
        }
        val password = (arguments["password"] as? JsonPrimitive)?.content
            ?.trim()?.takeIf { it.isNotEmpty() }
        val timeoutSec = ((arguments["timeout_sec"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 30)
            .coerceIn(5, 120)
        // WifiNetworkSpecifier was added in API 29 (Android 10):
        // https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext ToolResult(
                false,
                "Подключение через WifiNetworkSpecifier доступно на Android 10 (API 29) и выше; " +
                    "на этом устройстве API ${Build.VERSION.SDK_INT}.",
            )
        }
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return@withContext ToolResult(false, "ConnectivityManager недоступен на устройстве.")
        val callbackHandler = Handler(Looper.getMainLooper())
        val outcome = CompletableDeferred<ToolResult>()
        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password != null) {
            specifierBuilder.setWpa2Passphrase(password)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Do not require internet: the request must also match peer/P2P networks that
            // provide no internet access. Documented pattern for local-only Wi-Fi.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = try {
                    cm.getNetworkCapabilities(network)
                } catch (_: Exception) {
                    null
                }
                val validated =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                outcome.complete(
                    ToolResult(
                        true,
                        "Сеть Wi-Fi «$ssid» стала доступна приложению." +
                            (if (validated) {
                                " Интернет подтверждён системой."
                            } else {
                                " Интернет не подтверждён (это нормально для сети без выхода в сеть)."
                            }),
                    ),
                )
            }

            override fun onUnavailable() {
                outcome.complete(
                    ToolResult(
                        false,
                        "Система не нашла сеть «$ssid» или запрос был отклонён (например, пользователь " +
                            "отказал в подключении).",
                    ),
                )
            }

            override fun onLost(network: Network) {
                outcome.complete(ToolResult(false, "Сеть «$ssid» была потеряна до подтверждения."))
            }
        }
        try {
            cm.requestNetwork(request, callback, callbackHandler)
            withTimeoutOrNull(timeoutSec * 1000L) { outcome.await() }
                ?: ToolResult(false, "Таймаут $timeoutSec с: сеть «$ssid» не стала доступна.")
        } catch (_: SecurityException) {
            ToolResult(false, "PERMISSION_REQUIRED:${Manifest.permission.NEARBY_WIFI_DEVICES}")
        } catch (e: Exception) {
            ToolResult(false, "Ошибка подключения к «$ssid»: ${e.message}")
        } finally {
            // The specifier request is released either way: it must not keep the app bound to
            // the network after the tool returns.
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // Callback may already be unregistered; ignore.
            }
        }
    }
}
