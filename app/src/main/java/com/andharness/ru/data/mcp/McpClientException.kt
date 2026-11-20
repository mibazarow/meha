package com.andharness.ru.data.mcp

/**
 * A transport/protocol failure with an honest, user-facing Russian message. [httpCode] is set only
 * when the failure is an HTTP status (never for a JSON-RPC error), so the AUTO transport resolver
 * can tell a "this endpoint is not a Streamable HTTP server" status (400/404/405) apart from a
 * genuine protocol error that must be reported as-is.
 */
class McpClientException(
    message: String,
    val httpCode: Int? = null,
) : Exception(message)
