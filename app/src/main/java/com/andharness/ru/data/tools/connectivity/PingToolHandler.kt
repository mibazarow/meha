package com.andharness.ru.data.tools.connectivity

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/** `ping`: runs `/system/bin/ping` against a host with a bounded wait. */
internal class PingToolHandler : ToolHandler {
    override val name = "ping"
    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val host = (arguments["host"] as? JsonPrimitive)?.content?.trim()
                ?: return@withContext ToolResult(false, "Укажите host.")
            val count = (arguments["count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 4
            val process = ProcessBuilder(
                "/system/bin/ping", "-c", count.coerceIn(1, 10).toString(), "-W", "2", host
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroy()
            val suffix = if (finished) "" else "\n(таймаут)"
            ToolResult(true, output.take(4000).trim() + suffix)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
