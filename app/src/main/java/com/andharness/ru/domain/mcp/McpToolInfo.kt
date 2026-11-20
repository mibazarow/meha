package com.andharness.ru.domain.mcp

import kotlinx.serialization.json.JsonObject

/**
 * One tool advertised by a remote MCP server via `tools/list`.
 *
 * [inputSchema] is the JSON Schema of the tool's arguments exactly as the server sent it (or `null`
 * when the server omitted it / sent a non-object). It is kept raw here so the flattening into our
 * flat tool-param model stays a pure, unit-tested concern ([McpSchemaMapper]).
 *
 * Reference: https://modelcontextprotocol.io/specification/2025-06-18/server/tools
 */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject? = null,
)
