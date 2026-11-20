package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServerInfo
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.mcp.McpTransport

/**
 * Outcome of a full MCP connect probe: `initialize` handshake, `notifications/initialized`, then a
 * paginated `tools/list`. [Ok] carries the server's self-description, the transport that actually
 * worked, and every discovered tool.
 */
sealed interface McpConnectResult {
    data class Ok(
        val serverInfo: McpServerInfo,
        val transport: McpTransport,
        val tools: List<McpToolInfo>,
    ) : McpConnectResult

    data class Error(val message: String) : McpConnectResult
}
