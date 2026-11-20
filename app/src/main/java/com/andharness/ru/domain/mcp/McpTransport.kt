package com.andharness.ru.domain.mcp

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * How an MCP server is reached over HTTP.
 *
 * - [STREAMABLE_HTTP] — the current transport (protocol revision 2025-03-26+): every JSON-RPC
 *   message is one HTTP POST to the server URL; the response is either `application/json` or a
 *   request-scoped `text/event-stream`.
 * - [SSE] — the legacy HTTP+SSE transport (2024-11-05, deprecated): a long-lived `GET
 *   text/event-stream` that first advertises a POST `endpoint`, then carries responses as
 *   `message` events.
 * - [AUTO] — probe at connect time (POST first, fall back to the legacy SSE handshake on an
 *   HTTP 400/404/405 that is not a JSON-RPC error), exactly as the spec's backward-compatibility
 *   guidance describes.
 *
 * Reference: https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
 */
@Serializable
enum class McpTransport {
    AUTO,
    STREAMABLE_HTTP,
    SSE;

    companion object {
        /**
         * Lenient parse of a user/tool-supplied transport string. Accepts `auto`, `streamable`,
         * `streamable_http`, `http`, `sse`, case-insensitively and with `-`/space separators.
         * Returns `null` for an unknown non-blank value so the caller can report an honest error;
         * a blank value means [AUTO].
         */
        fun parse(raw: String?): McpTransport? {
            val clean = raw?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')
            return when (clean) {
                null, "" -> AUTO
                "auto" -> AUTO
                "streamable_http", "streamable", "http" -> STREAMABLE_HTTP
                "sse" -> SSE
                else -> null
            }
        }
    }
}
