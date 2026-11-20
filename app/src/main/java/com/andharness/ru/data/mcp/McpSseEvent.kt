package com.andharness.ru.data.mcp

/**
 * One dispatched Server-Sent Events event: its `event:` type (defaults to `message` when omitted)
 * and its joined `data:` payload.
 *
 * The MCP legacy HTTP+SSE transport uses `event: endpoint` to advertise the POST URL and
 * `event: message` for every JSON-RPC message.
 *
 * Reference: https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
 */
data class McpSseEvent(
    val event: String,
    val data: String,
) {
    companion object {
        /** Default event type when the stream omits `event:` — a JSON-RPC message. */
        const val MESSAGE = "message"

        /** The event that advertises the POST endpoint in the legacy HTTP+SSE transport. */
        const val ENDPOINT = "endpoint"
    }
}
