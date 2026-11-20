package com.andharness.ru.data.mcp

/**
 * Convenience wrapper over [McpSseAccumulator] that parses a complete SSE payload into its events.
 * Used to parse a fully-buffered `text/event-stream` response body (the Streamable HTTP transport
 * can answer a POST with one request-scoped SSE stream) and exercised on the JVM.
 *
 * Reference: https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation
 */
object McpSseParser {

    private val LINE_BREAKS = Regex("\r\n|\r|\n")

    /** Parses every dispatched event from [text], preserving order. */
    fun parseAll(text: String): List<McpSseEvent> {
        val accumulator = McpSseAccumulator()
        val events = mutableListOf<McpSseEvent>()
        text.split(LINE_BREAKS).forEach { raw ->
            accumulator.line(raw)?.let { events += it }
        }
        accumulator.flush()?.let { events += it }
        return events
    }
}
