package com.andharness.ru.data.mcp

/**
 * Line-oriented Server-Sent Events parser (WHATWG event-stream interpretation), pure and
 * Android-free so it is tested on the JVM (`McpSseParserTest`).
 *
 * Feed each line with [line]; it returns a [McpSseEvent] when a blank line dispatches the
 * accumulated event, otherwise `null`. Per the spec, a dispatch with an empty data buffer is
 * ignored, `:`-prefixed lines are comments, and multiple `data:` lines are joined with `\n`.
 *
 * Reference: https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
 */
class McpSseAccumulator {

    private val data = StringBuilder()
    private var event: String? = null
    private var hasData = false

    /** Consumes one line; returns the dispatched event at a blank line, else `null`. */
    fun line(raw: String): McpSseEvent? {
        if (raw.isEmpty()) return dispatch()
        if (raw.startsWith(":")) return null // comment / keep-alive
        val colon = raw.indexOf(':')
        val field = if (colon < 0) raw else raw.substring(0, colon)
        var value = if (colon < 0) "" else raw.substring(colon + 1)
        if (value.startsWith(" ")) value = value.removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            // `id` / `retry` are irrelevant for a request-scoped MCP stream.
            else -> Unit
        }
        return null
    }

    /** Dispatches any pending event (used when the stream ends without a trailing blank line). */
    fun flush(): McpSseEvent? = dispatch()

    private fun dispatch(): McpSseEvent? {
        if (!hasData) {
            event = null
            return null
        }
        val dispatched = McpSseEvent(event ?: McpSseEvent.MESSAGE, data.toString())
        data.setLength(0)
        hasData = false
        event = null
        return dispatched
    }
}
