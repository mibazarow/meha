package com.andharness.ru.domain.mcp

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonObject

/**
 * Dynamic exposure of the tools advertised by the user's ENABLED MCP servers.
 *
 * This is the seam between the MCP transport layer (`data/mcp`) and the agent's per-turn tool set
 * and single dispatch point. [toolDefinitions] returns one local [Tool] per remote tool
 * (`mcp_<server>_<tool>`, see [McpServerRules.qualifiedToolName]), and [execute] performs the
 * matching remote `tools/call`. The interface is Android-free so both the agent wiring and the JVM
 * tests can depend on it (production: `data/mcp/McpToolProvider`).
 *
 * Implementations MUST be fail-safe: an unreachable server must never throw out of
 * [toolDefinitions] — it is skipped (and logged) while the remaining servers keep working.
 */
interface McpDynamicTools {

    /**
     * Local tool definitions for every tool of every ENABLED, reachable MCP server, ordered by
     * server and then by the server's own listing order. Empty when no server is enabled/reachable.
     */
    suspend fun toolDefinitions(): List<Tool>

    /**
     * Dispatches one call to a dynamic MCP tool. Returns `null` when [toolName] is not a dynamic MCP
     * tool name at all (so the caller can keep its own "unknown tool" path); when the name has the
     * [PREFIX] but cannot be resolved, an honest failing [ToolResult] is returned.
     */
    suspend fun execute(toolName: String, arguments: JsonObject): ToolResult?

    /** Drops cached tool lists (all, or only [serverId]) so the next read reconnects. */
    fun invalidate(serverId: String? = null)

    /** Seeds the cache with a just-fetched list (e.g. right after `mcp_server_test`). */
    fun seed(server: McpServer, tools: List<McpToolInfo>)

    companion object {
        /** Prefix that marks every dynamically exposed remote tool (`mcp_<server>_<tool>`). */
        const val PREFIX = "mcp_"

        /** Hard cap on how many dynamic tools are exposed in total per turn. */
        const val MAX_DYNAMIC_TOOLS = 64
    }
}
