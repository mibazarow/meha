package com.andharness.ru.data.mcp

import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [McpClient] against a real OkHttp [MockWebServer], covering both transports
 * (Streamable HTTP with a JSON body and with a request-scoped SSE body; the legacy HTTP+SSE
 * handshake), `tools/list` pagination via `nextCursor`, the `Mcp-Session-Id` / `MCP-Protocol-Version`
 * headers, the AUTO backward-compatibility probe, and honest error mapping.
 */
class McpClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun serverModel(transport: McpTransport = McpTransport.STREAMABLE_HTTP, path: String = "/mcp") =
        McpServer(
            id = "id1",
            name = "demo",
            url = server.url(path).toString(),
            transport = transport,
        )

    private val initResult =
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",""" +
            """"serverInfo":{"name":"demo-server","version":"1.2.3"},"capabilities":{}}}"""

    @Test
    fun streamableHttpJsonHandshakeAndPagination() {
        server.enqueue(
            json(initResult).addHeader("Mcp-Session-Id", "sess-1")
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_weather","description":"w"}],"nextCursor":"c1"}}""")
        )
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"send_mail","description":"m"}]}}""")
        )

        val result = runBlocking { McpClient().test(serverModel(), token = null) }

        assertTrue(result is McpConnectResult.Ok)
        val ok = result as McpConnectResult.Ok
        assertEquals("demo-server", ok.serverInfo.name)
        assertEquals("1.2.3", ok.serverInfo.version)
        assertEquals(McpTransport.STREAMABLE_HTTP, ok.transport)
        assertEquals(listOf("get_weather", "send_mail"), ok.tools.map { it.name })

        val initialize = server.takeRequest()
        assertNull(initialize.getHeader("Mcp-Session-Id"))
        val notification = server.takeRequest()
        assertTrue(notification.body.readUtf8().contains("notifications/initialized"))
        val firstPage = server.takeRequest()
        assertEquals("sess-1", firstPage.getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", firstPage.getHeader("MCP-Protocol-Version"))
        assertEquals("application/json, text/event-stream", firstPage.getHeader("Accept"))
        val secondPage = server.takeRequest()
        assertTrue("cursor must be sent on the next page", secondPage.body.readUtf8().contains("\"cursor\":\"c1\""))
    }

    @Test
    fun streamableHttpAcceptsRequestScopedSseBody() {
        server.enqueue(
            sse("event: message\ndata: $initResult\n\n").addHeader("Mcp-Session-Id", "s2")
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            sse("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"ping\"}]}}\n\n")
        )

        val result = runBlocking { McpClient().test(serverModel(), token = null) }

        assertTrue(result is McpConnectResult.Ok)
        assertEquals(listOf("ping"), (result as McpConnectResult.Ok).tools.map { it.name })
        assertEquals(McpTransport.STREAMABLE_HTTP, result.transport)
    }

    @Test
    fun legacySseHandshakeUsesEndpointEvent() {
        val stream = buildString {
            append("event: endpoint\n")
            append("data: /messages?sessionId=sse-1\n\n")
            append("event: message\n")
            append("data: $initResult\n\n")
            append("event: message\n")
            append("data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"legacy_tool\"}]}}\n\n")
        }
        server.enqueue(sse(stream))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = runBlocking { McpClient().test(serverModel(McpTransport.SSE, path = "/sse"), token = null) }

        assertTrue(result is McpConnectResult.Ok)
        val ok = result as McpConnectResult.Ok
        assertEquals(McpTransport.SSE, ok.transport)
        assertEquals(listOf("legacy_tool"), ok.tools.map { it.name })

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/messages?sessionId=sse-1", post.path)
    }

    @Test
    fun autoFallsBackToLegacySseOnMethodNotAllowed() {
        server.enqueue(MockResponse().setResponseCode(405).setBody("Method Not Allowed"))
        val stream = buildString {
            append("event: endpoint\n")
            append("data: /messages\n\n")
            append("event: message\n")
            append("data: $initResult\n\n")
            append("event: message\n")
            append("data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"old_tool\"}]}}\n\n")
        }
        server.enqueue(sse(stream))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = runBlocking { McpClient().test(serverModel(McpTransport.AUTO, path = "/mcp"), token = null) }

        assertTrue(result is McpConnectResult.Ok)
        assertEquals(McpTransport.SSE, (result as McpConnectResult.Ok).transport)
        assertEquals(listOf("old_tool"), result.tools.map { it.name })
    }

    @Test
    fun autoDoesNotFallBackOnJsonRpcError() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"bad version"}}""")
        )

        val result = runBlocking { McpClient().test(serverModel(McpTransport.AUTO), token = null) }

        assertTrue(result is McpConnectResult.Error)
        assertTrue((result as McpConnectResult.Error).message.contains("bad version"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun httpErrorIsReportedHonestly() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = runBlocking { McpClient().test(serverModel(), token = null) }

        assertTrue(result is McpConnectResult.Error)
        val message = (result as McpConnectResult.Error).message
        assertTrue("honest HTTP status expected: $message", message.contains("500"))
    }

    @Test
    fun malformedJsonRpcIsReportedHonestly() {
        server.enqueue(json("this is not json"))

        val result = runBlocking { McpClient().test(serverModel(), token = null) }

        assertTrue(result is McpConnectResult.Error)
        assertTrue((result as McpConnectResult.Error).message.contains("некорректн"))
    }

    @Test
    fun unreachableServerIsReportedHonestly() {
        val model = serverModel()
        server.shutdown()

        val result = runBlocking { McpClient().test(model, token = null) }

        assertTrue(result is McpConnectResult.Error)
        assertTrue((result as McpConnectResult.Error).message.isNotBlank())
    }

    @Test
    fun customAuthHeaderIsSent() {
        server.enqueue(json(initResult).addHeader("Mcp-Session-Id", "s"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""))

        val model = serverModel().copy(authHeaderName = "Authorization")
        val result = runBlocking { McpClient().test(model, token = "secret-token") }
        assertTrue(result is McpConnectResult.Ok)
        val initialize = server.takeRequest()
        assertEquals("Bearer secret-token", initialize.getHeader("Authorization"))
    }
}
