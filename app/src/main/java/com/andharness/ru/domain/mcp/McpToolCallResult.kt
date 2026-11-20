package com.andharness.ru.domain.mcp

/**
 * The flattened outcome of a remote `tools/call`: the concatenated text of every `type: "text"`
 * content item plus the JSON-RPC `isError` flag.
 *
 * Reference: https://modelcontextprotocol.io/specification/2025-06-18/server/tools#calling-tools
 */
data class McpToolCallResult(
    val text: String,
    val isError: Boolean,
)
