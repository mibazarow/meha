package com.andharness.ru.data.tools.connectivity

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress

/** `dns_lookup`: resolves a host name to all its IP addresses. */
internal class DnsLookupToolHandler : ToolHandler {
    override val name = "dns_lookup"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val host = (arguments["host"] as? JsonPrimitive)?.content?.trim()
                ?: return@withContext ToolResult(false, "Укажите host.")
            val addresses = InetAddress.getAllByName(host)
            ToolResult(true, addresses.joinToString("\n") { it.hostAddress ?: it.toString() })
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
