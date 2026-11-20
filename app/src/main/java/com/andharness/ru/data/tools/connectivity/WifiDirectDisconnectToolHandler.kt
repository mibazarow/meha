package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** `wifi_direct_disconnect`: removes the Wi-Fi Direct group and cancels a pending connect. */
internal class WifiDirectDisconnectToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "wifi_direct_disconnect"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        access.nearbyWifiPermissionError()?.let { return@withContext ToolResult(false, it) }
        val p2p = context.getSystemService(WifiP2pManager::class.java)
            ?: return@withContext ToolResult(false, "Wi-Fi Direct недоступен на этом устройстве.")
        val channel = access.openP2pChannel(p2p)
            ?: return@withContext ToolResult(
                false,
                "Не удалось открыть канал Wi-Fi Direct (нет разрешений или служба недоступна).",
            )
        val removeGroupResult = CompletableDeferred<String>()
        @Suppress("DEPRECATION")
        p2p.removeGroup(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    removeGroupResult.complete("группа Wi-Fi Direct удалена")
                }

                override fun onFailure(reason: Int) {
                    removeGroupResult.complete("удалить группу не удалось (код $reason)")
                }
            },
        )
        val cancelConnectResult = CompletableDeferred<String>()
        @Suppress("DEPRECATION")
        p2p.cancelConnect(
            channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    cancelConnectResult.complete("ожидающее подключение отменено")
                }

                override fun onFailure(reason: Int) {
                    cancelConnectResult.complete("отменять было нечего или отмена не удалась (код $reason)")
                }
            },
        )
        val removeMessage = withTimeoutOrNull(P2P_ACTION_TIMEOUT_MS) { removeGroupResult.await() }
            ?: "нет ответа на removeGroup за ${P2P_ACTION_TIMEOUT_MS / 1000} с"
        val cancelMessage = withTimeoutOrNull(P2P_ACTION_TIMEOUT_MS) { cancelConnectResult.await() }
            ?: "нет ответа на cancelConnect за ${P2P_ACTION_TIMEOUT_MS / 1000} с"
        val succeeded = removeGroupResult.isCompleted && !removeMessage.startsWith("нет ответа") &&
            !removeMessage.contains("не удалось")
        ToolResult(
            succeeded,
            "Wi-Fi Direct: $removeMessage; $cancelMessage.\n" +
                "Примечание: если группа не была создана, код ошибки removeGroup — это норма.",
        )
    }

    private companion object {
        /** Bounded wait for a single Wi-Fi Direct action callback (removeGroup / cancelConnect). */
        private const val P2P_ACTION_TIMEOUT_MS = 5_000L
    }
}
