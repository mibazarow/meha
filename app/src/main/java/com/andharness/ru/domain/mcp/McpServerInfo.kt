package com.andharness.ru.domain.mcp

/**
 * The server's self-description returned in the `initialize` result (`serverInfo` + the negotiated
 * `protocolVersion`).
 *
 * Reference: https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle#initialization
 */
data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
)
