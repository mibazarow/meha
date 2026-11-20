package com.andharness.ru.data.tools.connectivity

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `hotspot_local_stop`: closes the local-only hotspot reservation. */
internal class HotspotLocalStopToolHandler(
    private val session: HotspotSession,
) : ToolHandler {
    override val name = "hotspot_local_stop"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val reservation = session.reservation
            ?: return@withContext ToolResult(
                false,
                "Локальная точка доступа не запущена — останавливать нечего.",
            )
        try {
            reservation.close()
        } catch (e: Exception) {
            return@withContext ToolResult(false, "Не удалось остановить локальную точку доступа: ${e.message}")
        }
        session.reservation = null
        // Android's public SDK has no API to read back the state of a local-only hotspot
        // (WifiManager exposes no getSoftApConfiguration()/getWifiApConfiguration()), so the
        // only observable confirmation is that close() returned without error. Reported
        // honestly instead of fabricating a verification.
        ToolResult(
            true,
            "Локальная точка доступа остановлена (резервирование закрыто без ошибок).\n" +
                "Android не даёт публичного API для чтения состояния локальной точки доступа, " +
                "поэтому подтверждение — только отсутствие ошибки close(); при сомнении проверьте " +
                "точку доступа в системных настройках.",
        )
    }
}
