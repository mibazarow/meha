package com.andharness.ru.data.tools.connectivity

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentLinkedQueue

/** `nsd_discover`: discovers mDNS/DNS-SD services of a given type for a bounded time. */
internal class NsdDiscoverToolHandler(
    private val context: Context,
) : ToolHandler {
    override val name = "nsd_discover"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val serviceType = normalizeServiceType((arguments["service_type"] as? JsonPrimitive)?.content)
        val timeoutSec = ((arguments["timeout_sec"] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 5)
            .coerceIn(1, 30)
        val nsd = context.getSystemService(NsdManager::class.java)
            ?: return@withContext ToolResult(false, "NsdManager недоступен на устройстве.")
        val found = ConcurrentLinkedQueue<String>()
        val finished = CompletableDeferred<Unit>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            found.add("${info.serviceName} — не удалось разрешить (код $errorCode)")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: "неизвестно"
                            found.add("${info.serviceName} | $host:${info.port}")
                        }
                    })
                } catch (e: Exception) {
                    found.add("${serviceInfo.serviceName} — ошибка разрешения: ${e.message}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                finished.complete(Unit)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.completeExceptionally(
                    IllegalStateException("не удалось запустить обнаружение (код $errorCode)")
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.completeExceptionally(
                    IllegalStateException("не удалось остановить обнаружение (код $errorCode)")
                )
            }
        }
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutSec * 1000L) { finished.await() }
            val results = found.toList()
            if (results.isEmpty()) {
                ToolResult(
                    true,
                    "Сервисы типа $serviceType не найдены за $timeoutSec с " +
                        "(multicast может фильтроваться сетью или сервисов нет)."
                )
            } else {
                ToolResult(
                    true,
                    "Найдено сервисов: ${results.size}\n" + results.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // Discovery may already be stopped; ignore.
            }
        }
    }

    /** Normalizes an NSD service type to the `_name._tcp.` form required by [NsdManager]. */
    private fun normalizeServiceType(raw: String?): String {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: "_http._tcp."
        return if (value.endsWith(".")) value else "$value."
    }
}
