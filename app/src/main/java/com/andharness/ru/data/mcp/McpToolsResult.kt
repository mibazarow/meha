package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpToolInfo

/** Outcome of a paginated `tools/list` against a connected MCP server. */
sealed interface McpToolsResult {
    data class Ok(val tools: List<McpToolInfo>) : McpToolsResult
    data class Error(val message: String) : McpToolsResult
}
