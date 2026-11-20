package com.andharness.ru.data.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure JSON-RPC 2.0 codec used by every MCP request/response. */
class McpJsonRpcTest {

    @Test
    fun requestCarriesIdAndMethod() {
        val body = McpJsonRpc.requestJson(7, "tools/list")
        assertTrue(body.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(body.contains("\"id\":7"))
        assertTrue(body.contains("\"method\":\"tools/list\""))
    }

    @Test
    fun requestCarriesParamsWhenPresent() {
        val params = buildJsonObject { put("cursor", "abc") }
        val body = McpJsonRpc.requestJson(1, "tools/list", params)
        assertTrue(body.contains("\"cursor\":\"abc\""))
    }

    @Test
    fun notificationHasNoId() {
        val body = McpJsonRpc.notificationJson("notifications/initialized")
        assertTrue(body.contains("\"method\":\"notifications/initialized\""))
        assertTrue("notifications MUST NOT carry an id: $body", !body.contains("\"id\""))
    }

    @Test
    fun parsesSuccessfulResponse() {
        val parsed = McpJsonRpc.parseResponse("""{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}""")
        assertTrue(parsed is McpRpcResponse.Success)
        assertEquals(3L, (parsed as McpRpcResponse.Success).id)
    }

    @Test
    fun parsesErrorResponse() {
        val parsed = McpJsonRpc.parseResponse(
            """{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"Method not found"}}"""
        )
        assertTrue(parsed is McpRpcResponse.Failure)
        parsed as McpRpcResponse.Failure
        assertEquals(-32601, parsed.code)
        assertEquals("Method not found", parsed.message)
        assertEquals(4L, parsed.id)
    }

    @Test
    fun errorWithoutIdIsStillAnError() {
        val parsed = McpJsonRpc.parseResponse(
            """{"jsonrpc":"2.0","error":{"code":-32000,"message":"crashed"}}"""
        )
        assertTrue(parsed is McpRpcResponse.Failure)
        assertEquals(null, (parsed as McpRpcResponse.Failure).id)
    }

    @Test
    fun malformedInputIsReportedHonestly() {
        assertTrue(McpJsonRpc.parseResponse("not json") is McpRpcResponse.Malformed)
        assertTrue(McpJsonRpc.parseResponse("""{"foo":"bar"}""") is McpRpcResponse.Malformed)
    }
}
