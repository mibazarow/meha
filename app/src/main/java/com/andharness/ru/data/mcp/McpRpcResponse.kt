package com.andharness.ru.data.mcp

import kotlinx.serialization.json.JsonElement

/**
 * The parsed outcome of one JSON-RPC 2.0 message received from an MCP server.
 *
 * [Success] carries the `result`; [Failure] a JSON-RPC `error` object; [Malformed] a message that
 * could not be parsed as valid JSON-RPC at all (so the client can report an honest error instead of
 * pretending the call succeeded).
 *
 * Reference: https://modelcontextprotocol.io/specification/2025-06-18/basic (Messages)
 */
sealed interface McpRpcResponse {
    data class Success(val id: Long?, val result: JsonElement) : McpRpcResponse
    data class Failure(val id: Long?, val code: Int, val message: String) : McpRpcResponse
    data class Malformed(val reason: String) : McpRpcResponse
}
