package com.andharness.ru.data.tools.connectivity

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.andharness.ru.data.tools.formatBytes
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/** `network_data_usage`: device-wide Wi-Fi/mobile traffic over a bounded day window. */
internal class NetworkDataUsageToolHandler(
    private val context: Context,
    private val access: ConnectivityAccess,
) : ToolHandler {
    override val name = "network_data_usage"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val days = ((arguments["days"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
                ?: DEFAULT_TRAFFIC_DAYS).coerceIn(1, MAX_TRAFFIC_DAYS)
            if (!access.hasUsageAccess()) {
                return@withContext ToolResult(
                    false,
                    SpecialAccess.required(SpecialAccess.USAGE_ACCESS),
                )
            }
            val networkStatsManager = context.getSystemService(NetworkStatsManager::class.java)
                ?: return@withContext ToolResult(false, "NetworkStatsManager недоступен на устройстве.")
            val end = System.currentTimeMillis()
            val start = end - TimeUnit.DAYS.toMillis(days.toLong())
            val wifi = try {
                @Suppress("MissingPermission")
                networkStatsManager.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, start, end)
            } catch (_: SecurityException) {
                return@withContext ToolResult(
                    false,
                    SpecialAccess.required(SpecialAccess.USAGE_ACCESS),
                )
            }
            val mobile = try {
                @Suppress("MissingPermission")
                networkStatsManager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end)
            } catch (_: SecurityException) {
                null
            }
            val wifiBytes = wifi?.let { it.rxBytes + it.txBytes } ?: 0L
            val mobileBytes = mobile?.let { it.rxBytes + it.txBytes } ?: 0L
            ToolResult(
                true,
                buildString {
                    appendLine("Общий трафик устройства за $days дн.:")
                    appendLine(
                        "Wi-Fi — принято ${formatBytes(wifi?.rxBytes ?: 0L)}, " +
                            "отправлено ${formatBytes(wifi?.txBytes ?: 0L)} " +
                            "(всего ${formatBytes(wifiBytes)})"
                    )
                    if (mobile != null) {
                        appendLine(
                            "Мобильная сеть — принято ${formatBytes(mobile.rxBytes)}, " +
                                "отправлено ${formatBytes(mobile.txBytes)} " +
                                "(всего ${formatBytes(mobileBytes)})"
                        )
                    } else {
                        appendLine("Мобильная сеть — данные недоступны")
                    }
                    append("Итого: ${formatBytes(wifiBytes + mobileBytes)}")
                }
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }

    private companion object {
        /** Default / maximum look-back window (days) for `network_data_usage`. */
        private const val DEFAULT_TRAFFIC_DAYS = 7
        private const val MAX_TRAFFIC_DAYS = 90
    }
}
