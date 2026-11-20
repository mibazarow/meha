package com.andharness.ru.data.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the WHATWG Server-Sent Events parsing used by the MCP transports. */
class McpSseParserTest {

    @Test
    fun parsesEndpointEvent() {
        val events = McpSseParser.parseAll("event: endpoint\ndata: /messages?sessionId=abc\n\n")
        assertEquals(1, events.size)
        assertEquals(McpSseEvent.ENDPOINT, events[0].event)
        assertEquals("/messages?sessionId=abc", events[0].data)
    }

    @Test
    fun defaultsToMessageEventWhenTypeOmitted() {
        val events = McpSseParser.parseAll("data: {\"jsonrpc\":\"2.0\"}\n\n")
        assertEquals(1, events.size)
        assertEquals(McpSseEvent.MESSAGE, events[0].event)
    }

    @Test
    fun joinsMultiLineDataWithNewlines() {
        val events = McpSseParser.parseAll("event: message\ndata: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun ignoresCommentsAndHandlesCrlf() {
        val events = McpSseParser.parseAll(": keep-alive\r\nevent: message\r\ndata: ok\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("ok", events[0].data)
    }

    @Test
    fun blankDispatchWithoutDataIsIgnored() {
        assertTrue(McpSseParser.parseAll("\n\n:\n\n").isEmpty())
    }

    @Test
    fun parsesSeveralEventsInOrder() {
        val text = "event: endpoint\ndata: /m\n\nevent: message\ndata: one\n\nevent: message\ndata: two\n\n"
        val events = McpSseParser.parseAll(text)
        assertEquals(listOf(McpSseEvent.ENDPOINT, McpSseEvent.MESSAGE, McpSseEvent.MESSAGE), events.map { it.event })
        assertEquals(listOf("/m", "one", "two"), events.map { it.data })
    }

    @Test
    fun flushesTrailingEventWithoutBlankLine() {
        val accumulator = McpSseAccumulator()
        accumulator.line("event: message")
        assertEquals(null, accumulator.line("data: tail"))
        assertEquals("tail", accumulator.flush()?.data)
    }
}
