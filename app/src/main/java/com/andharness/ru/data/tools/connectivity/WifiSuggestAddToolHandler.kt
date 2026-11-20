package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `wifi_suggest_add`: adds a Wi-Fi network suggestion the system may auto-connect to (API 29+). */
internal class WifiSuggestAddToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "wifi_suggest_add"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val ssid = (arguments["ssid"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (ssid.isEmpty()) {
            return@withContext ToolResult(false, "Укажите ssid сети Wi-Fi.")
        }
        val password = (arguments["password"] as? JsonPrimitive)?.content
            ?.trim()?.takeIf { it.isNotEmpty() }
        // WifiNetworkSuggestion was added in API 29 (Android 10):
        // https://developer.android.com/develop/connectivity/wifi/wifi-suggest
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext ToolResult(
                false,
                "Предложение сети через WifiNetworkSuggestion доступно на Android 10 (API 29) и выше; " +
                    "на этом устройстве API ${Build.VERSION.SDK_INT}.",
            )
        }
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password != null) {
                builder.setWpa2Passphrase(password)
            }
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
                ?: return@withContext ToolResult(false, "WifiManager недоступен на устройстве.")
            val status = wm.addNetworkSuggestions(listOf(builder.build()))
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                ToolResult(
                    true,
                    "Предложение сети «$ssid» принято системой (код $status). " +
                        "Решение о фактическом автоподключении принимает система, а не приложение; " +
                        "гарантий подключения нет.",
                )
            } else {
                ToolResult(
                    false,
                    "Система отклонила предложение сети «$ssid» (код $status). " +
                        "Возможные причины: превышен лимит предложений приложения, дубликат, " +
                        "или приложению запрещены предложения.",
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка предложения сети «$ssid»: ${e.message}")
        }
    }
}
