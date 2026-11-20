package com.andharness.ru.di.toolhandlers

import com.andharness.ru.data.mcp.McpClient
import com.andharness.ru.data.mcp.McpSecretStore
import com.andharness.ru.data.mcp.McpServerStore
import com.andharness.ru.data.tools.mcp.McpToolsHandler
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.tools.ToolHandler

/** MCP server management from chat: add / list / remove / test (see [McpToolsHandler]). */
internal fun mcpToolHandlers(
    mcpServerStore: McpServerStore,
    mcpSecretStore: McpSecretStore,
    mcpClient: McpClient,
    mcpDynamicTools: McpDynamicTools,
): List<ToolHandler> =
    McpToolsHandler(mcpServerStore, mcpSecretStore, mcpClient, mcpDynamicTools).handlers()
