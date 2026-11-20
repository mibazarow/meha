package com.andharness.ru.data.mcp

import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.mcp.McpSchemaMapper
import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpServerRules
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.tools.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The production [McpDynamicTools]: turns the tools advertised by ENABLED MCP servers into real
 * local tools the agent can call, and routes their calls back to the remote `tools/call`.
 *
 * Behaviour:
 * - **Caching.** Each server's tool list is cached in memory with a [TTL_MS] freshness window, so a
 *   chat turn does not reconnect to every server on every turn. A failed connection is cached for a
 *   shorter [RETRY_MS] window so an unreachable server is not retried on every single turn; if a
 *   previously-good list exists it is served (a transient blip never silently removes a capability).
 * - **Fail-safe.** A server that cannot be reached is skipped and logged honestly; the remaining
 *   servers keep working. Connection budgets are the [McpClient]'s own bounded OkHttp timeouts
 *   (connect 15 s / read-write 30 s), and every path runs on [Dispatchers.IO], never the main thread.
 * - **Bounded.** At most [McpDynamicTools.MAX_DYNAMIC_TOOLS] tools are exposed in total, and each
 *   remote name is sanitized/uniquified via [McpServerRules.qualifiedToolName].
 *
 * References:
 * - MCP tools (`tools/list`, `tools/call`) — https://modelcontextprotocol.io/specification/2025-06-18/server/tools
 * - JSON Schema — https://json-schema.org/understanding-json-schema/reference/object
 * - OkHttp (bounded timeouts) — https://square.github.io/okhttp/recipes/
 * - kotlinx.coroutines (Dispatchers.IO, Mutex) — https://kotlinlang.org/docs/coroutines-guide.html
 */
@Singleton
class McpToolProvider @Inject constructor(
    private val store: McpServerStore,
    private val tokens: McpTokenLookup,
    private val client: McpClient,
    private val timeProvider: TimeProvider,
) : McpDynamicTools {

    /** One cached `tools/list` result plus its expiry and whether it came from a successful probe. */
    private class Entry(val tools: List<McpToolInfo>, val expiresAt: Long, val ok: Boolean)

    /** Where one exposed local tool name comes from, so a call can be routed back. */
    private class Mapping(val server: McpServer, val info: McpToolInfo)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** Serializes refreshes so two turns cannot open duplicate sessions for the same server. */
    private val refreshLock = Mutex()

    /** Local tool name → remote origin, rebuilt by [toolDefinitions]. */
    @Volatile
    private var index: Map<String, Mapping> = emptyMap()

    override suspend fun toolDefinitions(): List<Tool> = withContext(Dispatchers.IO) {
        val servers = runCatching { store.list().filter { it.enabled } }.getOrDefault(emptyList())
        val definitions = ArrayList<Tool>()
        val nextIndex = HashMap<String, Mapping>()
        val usedNames = HashSet<String>()
        for (server in servers) {
            if (definitions.size >= McpDynamicTools.MAX_DYNAMIC_TOOLS) break
            val tools = toolsFor(server) ?: continue
            for (info in tools) {
                if (definitions.size >= McpDynamicTools.MAX_DYNAMIC_TOOLS) break
                val localName = uniqueLocalName(server, info.name, usedNames)
                definitions += Tool(
                    name = localName,
                    description = describe(server, info),
                    parameters = McpSchemaMapper.toParameterSchema(info.inputSchema),
                )
                nextIndex[localName] = Mapping(server, info)
            }
        }
        index = nextIndex
        if (definitions.isEmpty()) {
            Log.i(TAG, "MCP: динамических инструментов нет (включённых серверов: ${servers.size}).")
        } else {
            Log.i(TAG, "MCP: доступно динамических инструментов ${definitions.size}.")
        }
        definitions
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult? {
        if (!toolName.startsWith(McpDynamicTools.PREFIX)) return null
        var mapping = index[toolName]
        if (mapping == null) {
            toolDefinitions()
            mapping = index[toolName]
        }
        val resolved = mapping
            ?: return ToolResult(
                false,
                "MCP-инструмент «$toolName» недоступен: сервер не найден, отключён или не объявил " +
                    "такой инструмент. Проверьте связь: mcp_server_test."
            )
        return try {
            val token = tokens.tokenFor(resolved.server)
            val callArguments = McpSchemaMapper.buildArguments(resolved.info.inputSchema, arguments)
            when (val outcome = client.callTool(resolved.server, token, resolved.info.name, callArguments)) {
                is McpCallOutcome.Ok -> {
                    val result = outcome.result
                    ToolResult(
                        success = !result.isError,
                        output = if (result.isError) {
                            "MCP-инструмент «${resolved.info.name}» на сервере «${resolved.server.name}» " +
                                "вернул ошибку: ${result.text}"
                        } else {
                            result.text
                        }
                    )
                }
                is McpCallOutcome.Error -> ToolResult(false, outcome.message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка вызова MCP-инструмента «${resolved.info.name}»: ${e.message}")
        }
    }

    override fun invalidate(serverId: String?) {
        if (serverId.isNullOrBlank()) cache.clear() else cache.remove(serverId)
        index = emptyMap()
    }

    override fun seed(server: McpServer, tools: List<McpToolInfo>) {
        cache[server.id] = Entry(tools, timeProvider.nowMillis() + TTL_MS, ok = true)
        index = emptyMap()
    }

    /** Freshly cached tools for [server], or `null` when it is disabled/unreachable (skipped). */
    private suspend fun toolsFor(server: McpServer): List<McpToolInfo>? {
        val now = timeProvider.nowMillis()
        cache[server.id]?.let { entry ->
            if (now < entry.expiresAt) return if (entry.ok) entry.tools else null
        }
        return refreshLock.withLock {
            val current = cache[server.id]
            val after = timeProvider.nowMillis()
            if (current != null && after < current.expiresAt) {
                return@withLock if (current.ok) current.tools else null
            }
            when (val result = runCatching { client.listTools(server, tokens.tokenFor(server)) }.getOrNull()) {
                is McpToolsResult.Ok -> {
                    cache[server.id] = Entry(result.tools, after + TTL_MS, ok = true)
                    result.tools
                }
                else -> {
                    val message = (result as? McpToolsResult.Error)?.message ?: "неизвестная ошибка связи"
                    Log.w(TAG, "MCP «${server.name}» недоступен, инструменты пропущены: $message")
                    // Keep the last known good list (a blip must not drop a capability); retry sooner.
                    val stale = current?.takeIf { it.ok && it.tools.isNotEmpty() }
                    cache[server.id] = Entry(stale?.tools.orEmpty(), after + RETRY_MS, ok = stale != null)
                    stale?.tools
                }
            }
        }
    }

    /** `mcp_<server>_<tool>`, sanitized by the shared rules and made unique with a numeric suffix. */
    private fun uniqueLocalName(
        server: McpServer,
        remoteName: String,
        used: MutableSet<String>,
    ): String {
        val base = McpServerRules.qualifiedToolName(server, remoteName)
        if (used.add(base)) return base
        var suffix = 2
        while (true) {
            val marker = "_$suffix"
            val candidate = base.take(MAX_LOCAL_NAME_CHARS - marker.length) + marker
            if (used.add(candidate)) return candidate
            suffix++
        }
    }

    private fun describe(server: McpServer, info: McpToolInfo): String {
        val base = info.description.trim().ifBlank { "Инструмент MCP-сервера «${server.name}»." }
        val hint = McpSchemaMapper.renderHint(info.inputSchema)
        return buildString {
            append(base)
            append(" Аргументы передаются одним JSON-объектом.")
            if (hint.isNotBlank()) {
                append(' ')
                append(hint)
            }
        }.take(McpSchemaMapper.MAX_TOOL_DESCRIPTION_CHARS)
    }

    private companion object {
        private const val TAG = "Andharness"

        /** How long a successful tool list stays fresh (5 minutes). */
        const val TTL_MS = 5L * 60L * 1000L

        /** How long a failed/unreachable server is remembered before retrying (30 seconds). */
        const val RETRY_MS = 30L * 1000L

        /** Local tool names are bounded like [McpServerRules.qualifiedToolName]. */
        const val MAX_LOCAL_NAME_CHARS = 64
    }
}
